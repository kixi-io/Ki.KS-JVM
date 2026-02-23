package io.kixi.ks.interp

import io.kixi.ks.*
import io.kixi.ks.lexer.Lexer
import io.kixi.ks.parser.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Tests for GeoPoint native type member access from KS code.
 *
 * Covers constructors, static members, instance properties, instance methods,
 * type checking (`is`), reflection (`.type`, `.typeName`), and error conditions.
 *
 * Run with: ./gradlew test --tests "io.kixi.ks.interp.GeoPointNativeTypeTest"
 */
class GeoPointNativeTypeTest : FunSpec({

    // ====================================================================
    // Helpers
    // ====================================================================

    fun run(source: String): String {
        val output = StringWriter()
        val error = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(error, true),
            debugMode = false
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)
        interpreter.executeProgram(program)
        return output.toString().trim()
    }

    fun runExpectingError(source: String): String {
        val output = StringWriter()
        val runtime = KSRuntime(
            hostLang = false,
            colorOutput = false,
            outputWriter = PrintWriter(output, true),
            errorWriter = PrintWriter(StringWriter(), true)
        )
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter(runtime)

        try {
            interpreter.executeProgram(program)
            throw AssertionError("Expected an error but execution completed. Output: ${output.toString().trim()}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
    }

    // ====================================================================
    // Construction
    // ====================================================================

    context("GeoPoint - construction") {

        test("construct with lat, lon") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.lat
                say p.lon
            """.trimIndent()) shouldBe "37.7749\n-122.4194"
        }

        test("construct with lat, lon, altitude") {
            run("""
                let p = GeoPoint(35.6762, 139.6503, 40.0)
                say p.lat
                say p.lon
                say p.alt
            """.trimIndent()) shouldBe "35.6762\n139.6503\n40"
        }

        test("construct origin") {
            run("""
                let p = GeoPoint(0, 0)
                say p.isOrigin
            """.trimIndent()) shouldBe "true"
        }

        test("invalid latitude throws") {
            val error = runExpectingError("""
                GeoPoint(91.0, 0.0)
            """.trimIndent())
            error shouldContain "atitude"
        }

        test("invalid longitude throws") {
            val error = runExpectingError("""
                GeoPoint(0.0, 181.0)
            """.trimIndent())
            error shouldContain "ongitude"
        }

        test("wrong arg count throws") {
            val error = runExpectingError("""
                GeoPoint(37.0)
            """.trimIndent())
            error shouldContain "expects"
        }
    }

    // ====================================================================
    // Static Members
    // ====================================================================

    context("GeoPoint - static members") {

        test("GeoPoint.of with lat, lon") {
            run("""
                let p = GeoPoint.of(37.7749, -122.4194)
                say p.lat
                say p.lon
            """.trimIndent()) shouldBe "37.7749\n-122.4194"
        }

        test("GeoPoint.of with lat, lon, altitude") {
            run("""
                let p = GeoPoint.of(35.6762, 139.6503, 40.0)
                say p.hasAltitude
                say p.alt
            """.trimIndent()) shouldBe "true\n40"
        }

        test("GeoPoint.parse") {
            run("""
                let p = GeoPoint.parse(".geo(37.7749, -122.4194)")
                say p.lat
                say p.lon
            """.trimIndent()) shouldBe "37.7749\n-122.4194"
        }

        test("GeoPoint.parse with altitude") {
            run("""
                let p = GeoPoint.parse(".geo(35.6762, 139.6503, 40.0)")
                say p.hasAltitude
            """.trimIndent()) shouldBe "true"
        }

        test("GeoPoint.isLiteral true") {
            run("""
                say GeoPoint.isLiteral(".geo(37.7749, -122.4194)")
            """.trimIndent()) shouldBe "true"
        }

        test("GeoPoint.isLiteral false") {
            run("""
                say GeoPoint.isLiteral("not a geopoint")
            """.trimIndent()) shouldBe "false"
        }

        test("GeoPoint.ORIGIN") {
            run("""
                let p = GeoPoint.ORIGIN
                say p.isOrigin
                say p.lat
                say p.lon
            """.trimIndent()) shouldBe "true\n0\n0"
        }

        test("GeoPoint.NORTH_POLE") {
            run("""
                let p = GeoPoint.NORTH_POLE
                say p.lat
                say p.isNorthern
            """.trimIndent()) shouldBe "90\ntrue"
        }

        test("GeoPoint.SOUTH_POLE") {
            run("""
                let p = GeoPoint.SOUTH_POLE
                say p.lat
                say p.isSouthern
            """.trimIndent()) shouldBe "-90\ntrue"
        }

        test("GeoPoint.DEFAULT_PRECISION") {
            run("""
                say GeoPoint.DEFAULT_PRECISION
            """.trimIndent()) shouldBe "6"
        }

        test("GeoPoint.center") {
            run("""
                let points = [
                    GeoPoint(0.0, 0.0),
                    GeoPoint(0.0, 10.0)
                ]
                let c = GeoPoint.center(points)
                say c.lon > 4.0
                say c.lon < 6.0
            """.trimIndent()) shouldBe "true\ntrue"
        }
    }

    // ====================================================================
    // Instance Properties
    // ====================================================================

    context("GeoPoint - instance properties") {

        test("latitude and longitude as BigDecimal") {
            // latitude/longitude return BigDecimal, lat/lon return Double
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.latitude
                say p.longitude
            """.trimIndent()) shouldBe "37.7749\n-122.4194"
        }

        test("lat and lon as Double") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.lat
                say p.lon
            """.trimIndent()) shouldBe "37.7749\n-122.4194"
        }

        test("altitude and alt") {
            run("""
                let p = GeoPoint(35.6762, 139.6503, 100.5)
                say p.altitude
                say p.alt
            """.trimIndent()) shouldBe "100.5\n100.5"
        }

        test("altitude nil when not set") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.altitude
                say p.alt
            """.trimIndent()) shouldBe "nil\nnil"
        }

        test("hasAltitude") {
            run("""
                say GeoPoint(37.0, -122.0, 50.0).hasAltitude
                say GeoPoint(37.0, -122.0).hasAltitude
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("isOrigin") {
            run("""
                say GeoPoint(0, 0).isOrigin
                say GeoPoint(1.0, 0.0).isOrigin
            """.trimIndent()) shouldBe "true\nfalse"
        }

        test("hemisphere properties - northern") {
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                say sf.isNorthern
                say sf.isSouthern
                say sf.isWestern
                say sf.isEastern
            """.trimIndent()) shouldBe "true\nfalse\ntrue\nfalse"
        }

        test("hemisphere properties - southern and eastern") {
            run("""
                let sydney = GeoPoint(-33.8688, 151.2093)
                say sydney.isNorthern
                say sydney.isSouthern
                say sydney.isWestern
                say sydney.isEastern
            """.trimIndent()) shouldBe "false\ntrue\nfalse\ntrue"
        }
    }

    // ====================================================================
    // Instance Methods
    // ====================================================================

    context("GeoPoint - instance methods") {

        test("distanceTo returns reasonable distance") {
            // SF to Tokyo is roughly 8,280 km
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                let tokyo = GeoPoint(35.6762, 139.6503)
                let dist = sf.distanceTo(tokyo)
                say dist > 8000.0
                say dist < 9000.0
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("distanceTo self is zero") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.distanceTo(p) == 0.0
            """.trimIndent()) shouldBe "true"
        }

        test("bearingTo returns degrees") {
            run("""
                let p1 = GeoPoint(0.0, 0.0)
                let p2 = GeoPoint(1.0, 0.0)
                let b = p1.bearingTo(p2)
                // Due north should be ~0 degrees
                say b < 1.0
            """.trimIndent()) shouldBe "true"
        }

        test("bearingTo east") {
            run("""
                let p1 = GeoPoint(0.0, 0.0)
                let p2 = GeoPoint(0.0, 1.0)
                let b = p1.bearingTo(p2)
                // Due east should be ~90 degrees
                say b > 89.0
                say b < 91.0
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("destination returns a GeoPoint") {
            run("""
                let start = GeoPoint(0.0, 0.0)
                let dest = start.destination(111.0, 0.0)
                // ~111 km north from equator should be ~1 degree latitude
                say dest.lat > 0.9
                say dest.lat < 1.1
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("withAltitude") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p.hasAltitude
                let p2 = p.withAltitude(100.0)
                say p2.hasAltitude
                say p2.alt
                say p2.lat
            """.trimIndent()) shouldBe "false\ntrue\n100\n37.7749"
        }

        test("withoutAltitude") {
            run("""
                let p = GeoPoint(37.7749, -122.4194, 100.0)
                say p.hasAltitude
                let p2 = p.withoutAltitude()
                say p2.hasAltitude
                say p2.lat
            """.trimIndent()) shouldBe "true\nfalse\n37.7749"
        }

        test("withoutAltitude on point without altitude is no-op") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                let p2 = p.withoutAltitude()
                say p2.hasAltitude
            """.trimIndent()) shouldBe "false"
        }

        test("toDecimalDegrees") {
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                let dd = sf.toDecimalDegrees()
                say dd
            """.trimIndent()) shouldContain "N"
        }

        test("toDecimalDegrees directions") {
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                let dd = sf.toDecimalDegrees()
                say dd
            """.trimIndent()).let { result ->
                result shouldContain "N"
                result shouldContain "W"
            }
        }

        test("toDMS") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                let dms = p.toDMS()
                say dms
            """.trimIndent()).let { result ->
                result shouldContain "N"
                result shouldContain "W"
                result shouldContain "°"
            }
        }

        test("toCompactString") {
            run("""
                let p = GeoPoint(37.0, -122.0)
                let s = p.toCompactString()
                say s
            """.trimIndent()) shouldStartWith ".geo("
        }

        test("chained methods") {
            run("""
                let orig = GeoPoint(37.7749, -122.4194)
                let withAlt = orig.withAltitude(100.0)
                let p = withAlt.withoutAltitude()
                say p.hasAltitude
                say p.lat
            """.trimIndent()) shouldBe "false\n37.7749"
        }
    }

    // ====================================================================
    // Type Checking and Reflection
    // ====================================================================

    context("GeoPoint - type checking") {

        test("is GeoPoint") {
            run("""
                let p = GeoPoint(37.0, -122.0)
                say p is GeoPoint
            """.trimIndent()) shouldBe "true"
        }

        test("number is not GeoPoint") {
            run("""
                say 37.0 is GeoPoint
            """.trimIndent()) shouldBe "false"
        }

        test("!is GeoPoint") {
            run("""
                say "hello" !is GeoPoint
            """.trimIndent()) shouldBe "true"
        }
    }

    context("GeoPoint - reflection") {

        test("typeName") {
            run("""
                say GeoPoint(37.0, -122.0).typeName
            """.trimIndent()) shouldBe "GeoPoint"
        }

        test("type") {
            run("""
                say GeoPoint(37.0, -122.0).type
            """.trimIndent()) shouldBe "GeoPoint"
        }

        test("constructor type") {
            run("""
                say GeoPoint.type
            """.trimIndent()) shouldBe "class GeoPoint"
        }
    }

    // ====================================================================
    // Error Conditions
    // ====================================================================

    context("GeoPoint - error conditions") {

        test("member not found") {
            val error = runExpectingError("""
                GeoPoint(37.0, -122.0).nonExistent
            """.trimIndent())
            error shouldContain "nonExistent"
        }

        test("static not found") {
            val error = runExpectingError("""
                GeoPoint.noSuchStatic
            """.trimIndent())
            error shouldContain "noSuchStatic"
        }

        test("distanceTo requires GeoPoint") {
            val error = runExpectingError("""
                let p = GeoPoint(37.0, -122.0)
                let dt = p.distanceTo
                dt("not a point")
            """.trimIndent())
            error shouldContain "GeoPoint"
        }

        test("bearingTo requires GeoPoint") {
            val error = runExpectingError("""
                let p = GeoPoint(37.0, -122.0)
                let bt = p.bearingTo
                bt(42)
            """.trimIndent())
            error shouldContain "GeoPoint"
        }

        test("destination requires two args") {
            val error = runExpectingError("""
                let p = GeoPoint(37.0, -122.0)
                let dest = p.destination
                dest(100.0)
            """.trimIndent())
            error shouldContain "distanceKm"
        }

        test("parse invalid literal throws") {
            val error = runExpectingError("""
                GeoPoint.parse("not a geo literal")
            """.trimIndent())
            error shouldContain "geo"
        }
    }

    // ====================================================================
    // Integration
    // ====================================================================

    context("GeoPoint - integration") {

        test("store and access members") {
            run("""
                var p = GeoPoint(37.7749, -122.4194)
                say p.lat
                say p.lon
                say p.isNorthern
                say p.isWestern
            """.trimIndent()) shouldBe "37.7749\n-122.4194\ntrue\ntrue"
        }

        test("GeoPoint in collection") {
            run("""
                let cities = [
                    GeoPoint(37.7749, -122.4194),
                    GeoPoint(40.7128, -74.0060),
                    GeoPoint(51.5074, -0.1278)
                ]
                for p in cities {
                    say p.isNorthern
                }
            """.trimIndent()) shouldBe "true\ntrue\ntrue"
        }

        test("GeoPoint as function parameter") {
            run("""
                fun hemisphere(p: GeoPoint): String {
                    return when {
                        p.isNorthern -> "North"
                        p.isSouthern -> "South"
                        else -> "Equator"
                    }
                }
                say hemisphere(GeoPoint(37.0, -122.0))
                say hemisphere(GeoPoint(-33.0, 151.0))
                say hemisphere(GeoPoint(0, 0))
            """.trimIndent()) shouldBe "North\nSouth\nEquator"
        }

        test("GeoPoint as return value") {
            run("""
                fun withDefaultAlt(lat: Double, lon: Double): GeoPoint {
                    let g = GeoPoint(lat, lon)
                    return g.withAltitude(0.0)
                }
                let p = withDefaultAlt(37.7749, -122.4194)
                say p.hasAltitude
                say p.alt
            """.trimIndent()) shouldBe "true\n0"
        }

        test("distance calculation between cities") {
            // SF to NYC is roughly 4,130 km
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                let nyc = GeoPoint(40.7128, -74.0060)
                let dist = sf.distanceTo(nyc)
                say dist > 4000.0
                say dist < 4500.0
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("GeoPoint stringify in say") {
            run("""
                let p = GeoPoint(37.7749, -122.4194)
                say p
            """.trimIndent()) shouldStartWith ".geo("
        }

        test("GeoPoint in when with type check") {
            run("""
                let val1 = GeoPoint(37.0, -122.0)
                let val2 = "hello"
                fun describe(v): String = when {
                    v is GeoPoint -> "point"
                    v is String -> "text"
                    else -> "other"
                }
                say describe(val1)
                say describe(val2)
            """.trimIndent()) shouldBe "point\ntext"
        }

        test("center of cities") {
            run("""
                let sf = GeoPoint(37.7749, -122.4194)
                let nyc = GeoPoint(40.7128, -74.0060)
                let center = GeoPoint.center([sf, nyc])
                // Center should be roughly between the two latitudes
                say center.lat > 35.0
                say center.lat < 45.0
            """.trimIndent()) shouldBe "true\ntrue"
        }

        test("altitude manipulation chain") {
            run("""
                let sea = GeoPoint(37.0, -122.0)
                let high = sea.withAltitude(8848.0)
                let back = high.withoutAltitude()
                say sea.hasAltitude
                say high.alt
                say back.hasAltitude
                say back.lat
            """.trimIndent()) shouldBe "false\n8848\nfalse\n37"
        }
    }
})