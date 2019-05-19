import akka.http.scaladsl.model.headers.{Accept, Authorization, OAuth2BearerToken}
import sangria.macros._

import scala.concurrent.ExecutionContext.Implicits.global
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import sangria.marshalling.circe._

import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import sangria.parser.QueryParser

import scala.collection.immutable
import scala.concurrent.duration._


object Main {
  def main(args: Array[String]): Unit = {
    val GITHUB_ACCESS_TOKEN = scala.sys.env("GITHUB_ACCESS_TOKEN")
    println(s"#####################################################")
    println(s"GITHUB_ACCESS_TOKEN = '$GITHUB_ACCESS_TOKEN'")
    println(s"#####################################################")

    val repositoryOwner = "FabioPinheiro"
    val repositoryPlatform = "GitHubSuggestions"
    def query2GitHub(number:Int = 1) =
      QueryParser.parse(s"""query {
      repository(owner: "$repositoryOwner", name:"$repositoryPlatform") {
        pullRequest(number:$number) {
          title
        }
      }
    }""").get

    val queryJson = Map("query" -> ("query" + query2GitHub().renderCompact)).asJson.noSpaces
    println(queryJson)

    val requert = HttpRequest(
      method = HttpMethods.POST,
      headers = immutable.Seq[HttpHeader](
        Authorization(OAuth2BearerToken(GITHUB_ACCESS_TOKEN)),
        //Accept(MediaRange.apply(MediaType.))//HttpHeader.parse("Accept", "application/vnd.github.v4.idl")
      ),
      uri = "https://api.github.com/graphql",
      entity = HttpEntity(queryJson)
    )

    implicit val system = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val responseFuture: Future[String] = Http().singleRequest(requert)
      .flatMap(r => Unmarshal(r.entity).to[String])

    responseFuture
      .onComplete {
        case Success(res) => println(res)
        case Failure(_)   => sys.error("something wrong")
      }

    Await.ready(responseFuture,10.seconds)
    Await.ready(system.terminate(),10.seconds)
  }
}
