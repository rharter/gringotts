package xchange

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.route
import org.w3c.dom.NodeList
import xchange.db.Rates
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

fun Application.main() {
  val db = Database(this)

  install(CallLogging)
  install(DefaultHeaders)
  install(Routing) {
    get("refresh") {
      log.debug("Received request at /refresh")
      val channel: ByteArray = HttpClient(OkHttp).use { client ->
        client.get("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml")
      }

      val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(channel))

      val parser = XPathFactory.newInstance().newXPath()
      val dates = parser.evaluate("/Envelope/Cube/Cube", doc, XPathConstants.NODESET) as NodeList
      (0 until dates.length).map(dates::item).forEach { node ->
        val dateString = node.attributes.getNamedItem("time").textContent
        log.debug("Processing date: $dateString")
        val date = LocalDate.parse(dateString)

        val rates = (0 until node.childNodes.length).map(node.childNodes::item).map { rateNode ->
          Rates(
            date = date,
            currency = rateNode.attributes.getNamedItem("currency").textContent,
            rate = rateNode.attributes.getNamedItem("rate").textContent.toDouble()
          )
        }

        db.ratesQueries.transaction {
          val included = db.ratesQueries
            .selectByDateAndCurrency(rates.map { it.date }, rates.map { it.currency })
            .executeAsList()

          rates.forEach { rate ->
            val existing = included.firstOrNull { it.date == rate.date && it.currency == rate.currency }
            if (existing == rate) {
              log.debug("Skipping unchanged value for ${rate.currency} on ${rate.date}")
            } else if (existing != null) {
              log.debug("Updating ${rate.currency} rate for ${rate.date} from ${existing.rate} to ${rate.rate}")
              db.ratesQueries.updateRate(rate.rate, rate.date, rate.currency)
            } else {
              log.debug("Inserting ${rate.currency} rate for ${rate.date} as ${rate.rate}")
              db.ratesQueries.insertRate(rate)
            }
          }
        }
      }

      call.respondText("Updated ${dates.length} dates")
    }

    route("api") {
      get {
        call.respondText { db.ratesQueries.latestRates().executeAsList().joinToString("\n") }
      }
    }
  }
}
