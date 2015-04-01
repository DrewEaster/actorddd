package com.example

object ReleasesProtocol {

  import ReleaseProtocol._

  case class ReleaseDto(id: String, info: ReleaseInfo, currentDeployment: Option[Deployment], pastDeployments: Seq[Deployment])

  case class ReleasesDto(releases: Seq[ReleaseDto], total: Int)

  case class GetRelease(id: String)

  case class GetCurrentDeployment(releaseId: String)

  case object GetReleases
}
