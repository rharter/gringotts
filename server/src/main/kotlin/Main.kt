package xchange

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.serialization.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.w3c.dom.NodeList
import xchange.db.Rates
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

val baseRate = Rates("", "EUR", 1.0)

fun Application.main() {
  val db = Database(this)
  val apiSecret = environment.config.config("service").property("api_secret").getString()

  install(CallLogging)
  install(DefaultHeaders)
  install(ContentNegotiation) {
    json()
  }
  install(Routing) {
    get("healthz") {
      call.respondText("ok")
    }

    get("refresh") {
      if (call.request.queryParameters["api-key"] != apiSecret) {
        call.respond(HttpStatusCode.Unauthorized, "You are not authorized to access this endpoint.")
        return@get
      }

      val historical = call.request.queryParameters.contains("historical")

      val url = if (historical) {
        "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml"
      } else {
        "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"
      }

      val channel: ByteArray = HttpClient(OkHttp).use { it.get(url) }
      val doc = withContext(Dispatchers.IO) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(channel))
      }

      val parser = XPathFactory.newInstance().newXPath()
      val dates = parser.evaluate("/Envelope/Cube/Cube", doc, XPathConstants.NODESET) as NodeList
      (0 until dates.length).map(dates::item).forEach { node ->
        val date = node.attributes.getNamedItem("time").textContent
        log.debug("Processing date: $date")

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
            when (val existing = included.firstOrNull { it.date == rate.date && it.currency == rate.currency }) {
              rate -> log.debug("Skipping unchanged value for ${rate.currency} on ${rate.date}")
              null -> {
                log.debug("Inserting ${rate.currency} rate for ${rate.date} as ${rate.rate}")
                db.ratesQueries.insertRate(rate)
              }
              else -> {
                log.debug("Updating ${rate.currency} rate for ${rate.date} from ${existing.rate} to ${rate.rate}")
                db.ratesQueries.updateRate(rate.rate, rate.date, rate.currency)
              }
            }
          }
        }
      }

      call.respondText("Updated ${dates.length} dates")
    }

    route("api") {
      get("latest") {
        val base = call.request.queryParameters["base"] ?: "EUR"

        val rates = db.ratesQueries.latestRates().executeAsList()

        val baseRate = rates.find { it.currency == base }?.rate ?: 1.0
        val mappedRates = rates
          .map { it.currency to (1 / baseRate * it.rate) }
          .toMap()

        call.respond(RateResponse(base, rates.firstOrNull()?.date ?: "", mappedRates))
      }

      get("convert") {
        val from =
          call.request.queryParameters["from"] ?: throw IllegalArgumentException("Missing query parameter: from")
        val to = call.request.queryParameters["to"] ?: throw IllegalArgumentException("Missing query parameter: to")
        val amount = call.request.queryParameters["amount"]?.toDouble()
          ?: throw IllegalArgumentException("Missing query parameter: amount")

        val srcRate = if (from == baseRate.currency) baseRate else db.ratesQueries.selectByCurrency(from).executeAsOne()
        val dstRate = if (to == baseRate.currency) baseRate else db.ratesQueries.selectByCurrency(to).executeAsOne()

        val rate = 1.0 / srcRate.rate * dstRate.rate
        val date = if (srcRate.date.isNotBlank()) srcRate.date else dstRate.date

        call.respond(ConversionResult(date, amount * rate))
      }
    }
  }
}

@Serializable
data class ConversionResult(val date: String, val result: Double)

@Serializable
data class RateResponse(val base: String, val date: String, val rates: Map<String, Double>)
