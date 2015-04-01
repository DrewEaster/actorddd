package com.example

import java.util.UUID

import akka.actor.ActorRef
import akka.util.Timeout
import com.example.ReleasesProtocol._
import com.example.ReleaseProtocol._
import scala.concurrent.ExecutionContext.Implicits.global
import spray.http.StatusCodes.{NotFound, OK, InternalServerError, Created}
import spray.json.{JsString, JsObject, DefaultJsonProtocol}
import spray.routing._
import akka.pattern.ask
import spray.routing.directives.OnCompleteFutureMagnet
import scala.concurrent.duration._

import scala.util.{Failure, Success}

case class CreateReleaseDto()

case class UpdateDeployment(status: String)

object ReleaseJsonSupport extends DefaultJsonProtocol {
  implicit val DeploymentFormat = jsonFormat4(Deployment)
  implicit val ReleaseInfoFormat = jsonFormat(ReleaseInfo, "component_name", "version", "git_commit", "git_tag")
  implicit val ReleaseDtoFormat = jsonFormat(ReleaseDto, "id", "info", "current_deployment", "past_deployments")
  implicit val ReleasesDtoFormat = jsonFormat2(ReleasesDto)
}

trait ReleaseService extends HttpService {

  implicit val timeout = new Timeout(5 seconds)

  val releaseRegion: ActorRef

  val releasesView: ActorRef

  import ReleaseJsonSupport._
  import spray.httpx.SprayJsonSupport._

  val releasesRoute =
    pathPrefix("releases") {
      pathEnd {
        get {
          onComplete(getReleases) {
            case Success(releases) => complete(releases)
            case Failure(ex) => complete(InternalServerError, s"An error has occurred: ${ex.getMessage}")
          }
        } ~
          post {
            entity(as[ReleaseInfo]) { info =>
              respondWithStatus(Created) {
                complete {
                  val id = UUID.randomUUID().toString
                  releaseRegion ! CreateRelease(id, info)
                  JsObject("id" -> JsString(id))
                }
              }
            }
          }
      } ~
        pathPrefix(Segment) { releaseId =>
          pathEnd {
            get {
              onComplete(getRelease(releaseId)) {
                case Success(Some(release)) => complete(release)
                case Success(_) => complete(NotFound)
                case Failure(ex) => complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
              }
            }
          } ~
            path("deployments") {
              post {
                respondWithStatus(Created) {
                  complete {
                    releaseRegion ! StartDeployment(releaseId)
                    ""
                  }
                }
              } ~
                delete {
                  respondWithStatus(OK) {
                    complete {
                      releaseRegion ! EndDeployment(releaseId)
                      ""
                    }
                  }
                }
            }
        }
    }

  private def getReleases = OnCompleteFutureMagnet((releasesView ? GetReleases).mapTo[ReleasesDto])

  private def getRelease(id: String) = OnCompleteFutureMagnet((releasesView ? GetRelease(id)).mapTo[Option[ReleaseDto]])
}
