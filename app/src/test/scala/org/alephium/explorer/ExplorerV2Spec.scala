// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.explorer

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.TryValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import org.alephium.explorer.AlephiumSpec._
import org.alephium.explorer.GenCoreApi._
import org.alephium.explorer.GenCoreProtocol._
import org.alephium.explorer.config.{ApplicationConfig, ExplorerConfig}
import org.alephium.explorer.error.ExplorerError._
import org.alephium.explorer.persistence.{Database, DatabaseFixture}
import org.alephium.explorer.service.BlockFlowClient

/** Temporary placeholder. These tests should be merged into ApplicationSpec  */
class ExplorerV2Spec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckDrivenPropertyChecks
    with ScalaFutures
    with MockFactory {

  override implicit val patienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  implicit val executionContext: ExecutionContext =
    ExecutionContext.global

  "initialiseDatabase" should {
    "successfully connect" when {
      "readOnly mode" in {
        val databaseConfig = DatabaseConfig.forConfig[PostgresProfile]("db", DatabaseFixture.config)
        val database: Database =
          new Database(readOnly = true)(executionContext, databaseConfig)

        Try(database.startSelfOnce().futureValue) is Success(())
      }

      "readWrite mode" in {
        val databaseConfig = DatabaseConfig.forConfig[PostgresProfile]("db", DatabaseFixture.config)
        val database: Database =
          new Database(readOnly = false)(executionContext, databaseConfig)

        Try(database.startSelfOnce().futureValue) is Success(())
      }
    }
  }

  "getBlockFlowPeers" should {
    val explorerConfig: ExplorerConfig =
      ApplicationConfig.load().flatMap(ExplorerConfig(_)).success.value

    "return peer URIs" when {
      "directCliqueAccess = true" in {
        forAll(genSelfClique(Gen.nonEmptyListOf(genPeerAddress))) { selfClique =>
          implicit val client: BlockFlowClient = mock[BlockFlowClient]

          (client.fetchSelfClique _).expects() returns Future.successful(Right(selfClique))

          val expectedPeers =
            SyncServices.urisFromPeers(selfClique.nodes.toSeq)

          SyncServices
            .getBlockFlowPeers(directCliqueAccess = true,
                               blockFlowUri       = explorerConfig.blockFlowUri)
            .futureValue is expectedPeers
        }
      }

      "directCliqueAccess = false" in {
        implicit val client: BlockFlowClient = mock[BlockFlowClient]

        SyncServices
          .getBlockFlowPeers(directCliqueAccess = false, blockFlowUri = explorerConfig.blockFlowUri)
          .futureValue is Seq(explorerConfig.blockFlowUri)
      }
    }

    "fail" when {
      "no peers" in {
        //Generate data with no peers
        forAll(genSelfClique(peers = Gen.const(List.empty))) { selfClique =>
          implicit val client: BlockFlowClient = mock[BlockFlowClient]

          //expect call to fetchSelfClique because directCliqueAccess = true
          (client.fetchSelfClique _).expects() returns Future.successful(Right(selfClique))

          val result =
            SyncServices
              .getBlockFlowPeers(directCliqueAccess = true,
                                 blockFlowUri       = explorerConfig.blockFlowUri)
              .failed
              .futureValue

          //expect PeersNotFound exception
          result is PeersNotFound(explorerConfig.blockFlowUri)
          //exception message should contain the Uri
          result.getMessage should include(explorerConfig.blockFlowUri.toString())
        }
      }
    }
  }

  "validateChainParams" should {
    "succeed" when {
      "networkId matches" in {
        val matchingNetworkId =
          for {
            networkId   <- genNetworkId
            chainParams <- genChainParams(networkId)
          } yield (networkId, chainParams) //generate matching networkId

        forAll(matchingNetworkId) {
          case (networkId, chainParams) =>
            SyncServices.validateChainParams(networkId, Right(chainParams)) is Success(())
        }
      }
    }

    "fail" when {
      "networkId is a mismatch" in {
        val mismatchedNetworkId =
          for {
            networkId   <- genNetworkId
            chainParams <- genChainParams(genNetworkId(exclude = networkId))
          } yield (networkId, chainParams)

        forAll(mismatchedNetworkId) {
          case (networkId, chainParams) =>
            SyncServices
              .validateChainParams(networkId, Right(chainParams))
              .failure
              .exception is ChainIdMismatch(remote = chainParams.networkId, local = networkId)
        }
      }

      "response was an error" in {
        forAll(genNetworkId, Arbitrary.arbitrary[String]) {
          case (networkId, error) =>
            SyncServices
              .validateChainParams(networkId, Left(error))
              .failure
              .exception is ImpossibleToFetchNetworkType(error)
        }
      }
    }
  }
}