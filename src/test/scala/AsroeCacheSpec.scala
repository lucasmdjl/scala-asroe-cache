/*
 * Asroe Cache - An Async Self-Refreshing One-Element Cache micro-library
 * Copyright (C) 2025 Lucas de Jong
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lucasmdjl.scala.asroecache

import org.scalacheck.{Gen, Shrink}
import org.scalactic.anyvals.PosInt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class AsroeCacheSpec extends AnyFlatSpec with ScalaCheckPropertyChecks {

  private val minSuccessful = PosInt(1000)
  private val paramsPresentGen = for {
    expiration <- Gen.const(1000)
    expirationMargin <- Gen.oneOf(0, expiration / 2)
    advanceTime <- Gen.oneOf(
      0,
      expiration / 4,
      expiration / 2,
      3 * expiration / 4,
      expiration,
      5 * expiration / 4
    )
    failedThreads <- Gen.choose(0, 10)
    successThreads <- Gen.choose(0, 10)
    if failedThreads != 0 || successThreads != 0
  } yield (
    expiration,
    expirationMargin,
    advanceTime,
    failedThreads,
    successThreads
  )
  private val paramsMissingGen = for {
    expiration <- Gen.const(1000)
    expirationMargin <- Gen.oneOf(0, expiration / 2)
    failedThreads <- Gen.choose(0, 10)
    successThreads <- Gen.choose(0, 10)
    if failedThreads != 0 || successThreads != 0
  } yield (
    expiration,
    expirationMargin,
    failedThreads,
    successThreads
  )
  private val paramsAlreadyExpiredMissingGen = for {
    expiration <- Gen.oneOf(0, -1000)
    expirationMargin <- Gen.oneOf(0, -expiration / 2)
  } yield (
    expiration,
    expirationMargin
  )
  private val paramsExpireGen = for {
    expiration <- Gen.const(1000)
    expirationMargin <- Gen.oneOf(0, expiration / 2)
    advanceTime <- Gen.oneOf(
      0,
      expiration / 4,
      expiration / 2,
      3 * expiration / 4,
      expiration,
      5 * expiration / 4
    )
  } yield (
    expiration, expirationMargin, advanceTime
  )

  def missingTest(
      expiration: Long,
      expirationMargin: Long,
      failedThreads: Int,
      successThreads: Int
  ): Unit = {
    val testHarness = MissingTestHarness(
      expiration,
      expirationMargin,
      failedThreads,
      successThreads
    )
    val testResult = testHarness.run()

    assertResult(successThreads, "successful calls")(
      testResult.results.count(_.isSuccess)
    )
    assert(
      testResult.results
        .filter(_.isSuccess)
        .forall(_.get == testResult.newValue),
      "expected all successful threads to return new value"
    )

    assertResult(testResult.expectedFetches, "fetches")(
      testResult.observedFetches
    )
    assertResult(testResult.expectedFetches, "after fetch callbacks")(
      testResult.observedAfterFetchCallbacks
    )
    assertResult(testResult.expectedBackgroundErrors, "background errors")(
      testResult.observedBackgroundErrors
    )

    if (successThreads > 1) {
      assertResult(
        testResult.newValue,
        "expected new value to have propagated"
      )(testHarness.verifyNewValuePropagation())
    }
  }

  "AsroeCache::get when not empty" should "work under all valid parameters" in {
    forAll(paramsPresentGen, minSuccessful(minSuccessful)) {
      (expiration, margin, advance, fails, successes) =>
        presentTest(
          expiration,
          margin,
          advance,
          fails,
          successes
        )
        true
    }(using
      shrA = Shrink(_ => Stream.empty)
    )
  }

  def presentTest(
      expiration: Long,
      expirationMargin: Long,
      advanceTime: Long,
      failedThreads: Int,
      successThreads: Int
  ): Unit = {
    val testHarness = PresentTestHarness(
      expiration,
      expirationMargin,
      advanceTime,
      failedThreads,
      successThreads
    )
    testHarness.initialize()
    val testResult = testHarness.run()

    testResult.initialCacheState match {
      case CacheState.Fresh | CacheState.Stale =>
        assert(
          testResult.results.forall(_.isSuccess),
          "expected all successes"
        )
        assert(
          testResult.results.forall(_.get == testResult.oldValue),
          "expected all to have old value"
        )
      case CacheState.Expired =>
        assertResult(successThreads, "fetch successes")(
          testResult.results.count(_.isSuccess)
        )
        assert(
          testResult.results
            .filter(_.isSuccess)
            .forall(_.get == testResult.newValue),
          "expected all successes to have new value"
        )
      case CacheState.Missing => fail("Illegal initial cache state Missing")
    }

    assertResult(testResult.expectedFetches, "fetches")(
      testResult.observedFetches - 1
    ) // Account for cache priming
    assertResult(testResult.expectedFetches, s"after fetch callbacks")(
      testResult.observedAfterFetchCallbacks - 1
    ) // Account for cache priming
    assertResult(testResult.expectedBackgroundErrors, s"background errors")(
      testResult.observedBackgroundErrors
    )

    if (
      testResult.initialCacheState != CacheState.Fresh && successThreads >= 1
    ) {
      assertResult(
        testResult.newValue,
        "expected new value to have propagated"
      )(testHarness.verifyNewValuePropagation())
    }
  }

  "AsroeCache::get when empty" should "work under all valid parameters" in {
    forAll(
      paramsMissingGen,
      minSuccessful(minSuccessful)
    ) { (expiration, margin, fails, successes) =>
      missingTest(
        expiration,
        margin,
        fails,
        successes
      )
      true
    }(using
      shrA = Shrink(_ => Stream.empty)
    )
  }

  def alreadyExpiredTest(
      expiration: Long,
      expirationMargin: Long
  ): Unit = {
    require(expiration <= 0)
    val testHarness = MissingTestHarness(
      expiration,
      expirationMargin,
      failedThreads = 0,
      successThreads = 1
    )
    val testResult = testHarness.run()

    assertResult(1, "results")(testResult.results.size)
    val result = testResult.results.head
    assert(result.isFailure, "result failed")
    assertThrows[ElementAlreadyExpired](result.get)
  }

  "AsroeCache::get when result expired" should "fail with ElementAlreadyExpired" in {
    forAll(paramsAlreadyExpiredMissingGen, minSuccessful(minSuccessful)) {
      (expiration, margin) =>
        alreadyExpiredTest(
          expiration = expiration,
          expirationMargin = margin
        )
    }(using
      shrA = Shrink(_ => Stream.empty)
    )
  }

  def expireTest(
                  expiration: Long,
                  expirationMargin: Long,
                  advanceTime: Long): Unit = {
    val testHarness = ExpireTestHarness(
      expiration = expiration, expirationMargin = expirationMargin, advanceTime = advanceTime
    )

    testHarness.initialize()
    val testResult = testHarness.run()

    assertResult(1, "results")(testResult.results.size)
    val result = testResult.results.head
    assert(result.isSuccess, "result failed")
    assertResult(testResult.newValue, "result")(result.get)

    assertResult(testResult.expectedFetches, "fetches")(
      testResult.observedFetches - 1
    ) // Account for cache priming
    assertResult(testResult.expectedFetches, s"after fetch callbacks")(
      testResult.observedAfterFetchCallbacks - 1
    ) // Account for cache priming
    assertResult(testResult.expectedBackgroundErrors, s"background errors")(
      testResult.observedBackgroundErrors
    )

      assertResult(
        testResult.newValue,
        "expected new value to have propagated"
      )(testHarness.verifyNewValuePropagation())
  }

  "AsroeCache::expire" should "expire cached element" in {
    forAll(paramsExpireGen, minSuccessful(1)) {
      (expiration, margin, advanceTime) =>
        expireTest(
          expiration = expiration,
          expirationMargin = margin,
          advanceTime = advanceTime
        )
    }(using
      shrA = Shrink(_ => Stream.empty)
    )
  }

}
