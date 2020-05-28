package org.alephium.explorer.web

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import sttp.tapir.server.akkahttp.RichAkkaHttpEndpoint

import org.alephium.explorer.api.BlockEndpoints
import org.alephium.explorer.service.BlockService

class BlockServer(blockService: BlockService) extends Server with BlockEndpoints {
  val route: Route =
    getBlockById.toRoute(blockService.getBlockById) ~
      listBlocks.toRoute(blockService.listBlocks)
}
