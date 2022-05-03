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

package org.alephium.explorer.service

import scala.concurrent.{ExecutionContext, Future}

import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import slick.basic.DatabaseConfig
import slick.dbio.DBIOAction
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.foldFutures
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model.AppState
import org.alephium.explorer.persistence.schema.AppStateSchema
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.serde._
import org.alephium.util.{Duration, TimeStamp}

/*
 * Syncing mempool
 */

trait FinalizerService extends SyncService.BlockFlow

object FinalizerService extends StrictLogging {

  // scalastyle:off magic.number
  val finalizationDuration: Duration = Duration.ofSecondsUnsafe(6500)
  def finalizationTime: TimeStamp    = TimeStamp.now().minusUnsafe(finalizationDuration)
  def rangeStep: Duration            = Duration.ofHoursUnsafe(24)
  // scalastyle:on magic.number

  def apply(syncPeriod: Duration, databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit executionContext: ExecutionContext): FinalizerService =
    new Impl(syncPeriod, databaseConfig)

  private class Impl(val syncPeriod: Duration, val databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit val executionContext: ExecutionContext)
      extends FinalizerService
      with DBRunner {

    override def syncOnce(): Future[Unit] = {
      logger.debug("Finalizing")
      finalizeOutputs(databaseConfig)
    }
  }

  def finalizeOutputs(databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit executionContext: ExecutionContext): Future[Unit] = {
    DBRunner.run(databaseConfig)(getStartEndTime()).flatMap {
      case Some((start, end)) =>
        finalizeOutputsWith(start, end, rangeStep, databaseConfig)
      case None =>
        Future.successful(())
    }
  }

  def finalizeOutputsWith(start: TimeStamp,
                          end: TimeStamp,
                          step: Duration,
                          databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit executionContext: ExecutionContext): Future[Unit] = {
    var updateCounter = 0
    logger.debug(s"Updating outputs")
    val timeRanges =
      BlockFlowSyncService.buildTimestampRange(start, end, step)
    foldFutures(timeRanges) {
      case (from, to) =>
        DBRunner
          .run(databaseConfig)(
            (
              for {
                nb <- updateOutputs(from, to)
                _  <- updateLastFinalizedInputTime(to)
              } yield nb
            ).transactionally
          )
          .map { nb =>
            updateCounter = updateCounter + nb
            logger.debug(s"$updateCounter outputs updated")
          }
    }.map(_ => logger.debug(s"Outputs updated"))
  }

  private def updateOutputs(from: TimeStamp, to: TimeStamp): DBActionR[Int] = {
    sqlu"""
      UPDATE outputs o
      SET spent_finalized = i.tx_hash
      FROM inputs i
      WHERE i.output_ref_key = o.key
      AND o.main_chain=true
      AND i.main_chain=true
      AND i.block_timestamp >= $from
      AND i.block_timestamp < $to;
      """
  }

  def getStartEndTime()(
      implicit executionContext: ExecutionContext): DBActionR[Option[(TimeStamp, TimeStamp)]] = {
    val ft = finalizationTime
    getMinMaxInputsTs.flatMap(_.headOption match {
      //No input in db
      case None | Some((TimeStamp.zero, TimeStamp.zero)) => DBIOAction.successful(None)
      // only one input
      case Some((start, end)) if start == end => DBIOAction.successful(None)
      // inputs are only after finalization time, noop
      case Some((start, _)) if ft.isBefore(start) => DBIOAction.successful(None)
      case Some((start, _end)) =>
        val end = if (_end.isBefore(ft)) _end else ft
        getLastFinalizedInputTime().map {
          case None =>
            Some((start, end))
          case Some(lastFinalizedInputTime) =>
            Some((lastFinalizedInputTime, end))
        }
    })
  }

  private val getMinMaxInputsTs: DBActionSR[(TimeStamp, TimeStamp)] = {
    sql"""
    SELECT MIN(block_timestamp),Max(block_timestamp) FROM inputs WHERE main_chain = true
    """.as[(TimeStamp, TimeStamp)]
  }

  private def getLastFinalizedInputTime()(
      implicit executionContext: ExecutionContext): DBActionR[Option[TimeStamp]] = {
    sql"""
    SELECT value FROM app_state where key = 'last_finalized_input_time'
    """
      .as[ByteString]
      .map(_.headOption.flatMap { bytes =>
        deserialize[TimeStamp](bytes) match {
          case Left(error) =>
            logger.error(s"Cannot deserialize `last_finalized_input_time`: $error")
            None
          case Right(timestamp) => Some(timestamp)
        }
      })
  }

  private def updateLastFinalizedInputTime(time: TimeStamp) = {
    AppStateSchema.table.insertOrUpdate(AppState("last_finalized_input_time", serialize(time)))
  }
}