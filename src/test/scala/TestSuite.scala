import cats.effect._
import mx.cinvestav.DefaultConfig
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.blaze.client.{BlazeClient, BlazeClientBuilder}
import pureconfig._
import pureconfig.generic.auto._
import fs2.Stream
import mx.cinvestav.Declarations.{FilePayload, Metadata}
import org.http4s.multipart.{Multipart, Part}
import org.typelevel.ci.CIStringSyntax
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.parse
import org.http4s.headers.`Content-Type`

import java.nio.charset.StandardCharsets
import java.util.Base64.{getDecoder, getEncoder}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
class TestSuite extends munit .CatsEffectSuite {
  implicit val config = ConfigSource.default.loadOrThrow[DefaultConfig]
  val filePayload0 = FilePayload(
    fileId = UUID.randomUUID().toString,
    filename = "dog.txt",
    name="my_dog",
    extension = "txt",
    size=0L,
    timestamp =  0L
  )
  val filePayload1 = FilePayload(
    fileId = UUID.randomUUID().toString,
    filename = "cat.txt",
    name="my_cat",
    extension = "txt",
    size=0L,
    timestamp =  0L
  )
  test("Encoding/Decoding"){
    val r0 = filePayload0.asJson
    println(r0)
    val r1 = r0.noSpaces
    println(r1)
    val r2 = r1.getBytes
    println(r2.mkString("Array(", ", ", ")"))
    val r3 = getEncoder.encodeToString(r2)
    println(r3)
    val r4 = getDecoder.decode(r3)
    println(r4.mkString("Array(", ", ", ")"))
    val r5 = new String(r4)
    val r6 = parse(r5)
    r6 match {
      case Left(value) => IO.println(s"MAL: ${value}")
      case Right(value) => IO.println(value)

    }
  }
  test("Basics"){

    BlazeClientBuilder[IO](global).resource.use{ client =>
      val BASE64Encoder = getEncoder

      val uriStr = s"http://${config.host}:${config.port}/upload"
      val multipart = Multipart[IO](
        parts = Vector(
          Part[IO](
            headers = Headers(
              Header.Raw(ci"payload",BASE64Encoder.encodeToString(filePayload0.asJson.noSpaces.getBytes)  ),
              `Content-Type`(MediaType.text.plain)
            ),
            body = Stream.emits("HOLAAA".getBytes)
          ),
          Part[IO](
            headers = Headers(
              Header.Raw(ci"payload",BASE64Encoder.encodeToString(filePayload1.asJson.noSpaces.getBytes(StandardCharsets.UTF_8) )  ),
              `Content-Type`(MediaType.text.plain)
            ),
            body = Stream.emits("HOLAAA".getBytes)
          )
        ),
      )
      val metadataHeaders =  Headers(
                      Header.Raw(ci"userId","7db2ee89-381b-4998-a3c0-2ff771f4c628"),
                      Header.Raw(ci"sessionToken","db8308d5-41b2-4757-bbd5-c1cca2b9d7ed"),
      )
      val request = Request[IO](
        method = POST,
        uri = Uri.unsafeFromString(uriStr),
//        headers =
      )
        .withEntity(multipart)
        .withHeaders(headers = multipart.headers.headers ++ metadataHeaders.headers)
       client.expect[String](req = request).flatMap{x=>
         IO.println(x)
       }

    }
  }

}
