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

package org.alephium.explorer.util

import java.time._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import org.alephium.explorer.AlephiumSpec._

class TimeUtilSpec extends AnyWordSpec with Matchers {

  "toZonedDateTime" should {
    "convert OffsetTime to ZonedDateTime with today's date" in {
      val zone         = ZoneId.of("Australia/Perth")
      val expectedDate = LocalDateTime.now(zone) //expected day/month/year
      val expectedTime = OffsetTime.now(zone) //expected hour/minute/day

      val actual = TimeUtil.toZonedDateTime(expectedTime) //actual ZonedDateTime

      //check time
      actual.getHour is expectedTime.getHour
      actual.getMinute is expectedTime.getMinute
      actual.getSecond is expectedTime.getSecond
      //check year
      actual.getDayOfYear is expectedDate.getDayOfYear
      actual.getMonth is expectedDate.getMonth
      actual.getYear is expectedDate.getYear
    }
  }
}
