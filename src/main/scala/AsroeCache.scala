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

import dev.lucasmdjl.scala.clock.Clock

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private enum CacheResult[+T]:
  case Fresh(value: Expirable[T])
  case Stale(value: Expirable[T], refreshing: Future[Expirable[T]])
  case Expired(refreshing: Future[Expirable[T]])
  case Missing(refreshing: Future[Expirable[T]])

/** Exception thrown when a fetch operation returns data that is already
  * expired.
  *
  * This exception is thrown by the cache when the user-provided fetch function
  * returns an Expirable whose expiration time has already passed according to
  * the configured clock. This prevents the cache from storing stale data and
  * ensures data integrity.
  *
  * This typically indicates one of the following issues:
  *   - The fetch function is setting expiration times in the past
  *   - There's significant clock skew between systems
  *   - The fetch operation took longer than the data's intended lifetime
  *   - The data source is returning pre-expired data
  *
  * @example
  *   {{{
  *   // This would trigger ElementAlreadyExpired
  *   val cache = AsroeCache[String](
  *     expirationMargin = 1000,
  *     fetch = Future {
  *       val pastTime = System.currentTimeMillis() - 5000 // 5 seconds ago
  *       ExpirableBox("data", pastTime) // Already expired!
  *     }
  *   )
  *   }}}
  */
class ElementAlreadyExpired extends RuntimeException()

/** Async Self-Refreshing One-Element Cache.
  *
  * A thread-safe cache that holds a single value and automatically refreshes it
  * when it becomes stale. It uses an expiration margin to distinguish between:
  *   - Fresh data (served immediately)
  *   - Stale but usable data (served immediately and triggers background
  *     refresh)
  *   - Expired data (blocks until fresh data is available)
  *
  * Key features:
  *   - Non-blocking operations when stale data is acceptable
  *   - Single fetch guarantee: only one fetch operation runs at a time
  *   - Background refresh for optimal performance
  *   - Covariant in T for flexible usage
  *   - Configurable callbacks for fetch lifecycle events
  *   - Built-in error handling for background operations
  *
  * @param expirationMargin
  *   milliseconds before expiration to trigger background refresh
  * @param fetch
  *   function that fetches fresh data, takes an implicit ExecutionContext
  * @param afterFetchCallback
  *   partial function called after each fetch attempt with the result
  * @param onBackgroundError
  *   function called when background refresh operations fail
  * @param clock
  *   time source for expiration calculations
  * @tparam T
  *   the type of cached values
  * @example
  *   {{{
  * val cache = AsroeCache[String]( expirationMargin = 5000, // 5 seconds
  *   fetch = Future {
  *     val data = expensiveOperation()
  *     ExpirableBox(data, System.currentTimeMillis() + 60000) // expires in 1 minute
  *   },
  *   afterFetchCallback = {
  *     case Success(exp) => println(s"Fetched:${exp.value}")
  *     case Failure(ex) => println(s"Fetch failed:${ex.getMessage}")
  *   },
  *   onBackgroundError = ex => logger.warn("Background refresh failed", ex)
  * )
  *
  * val result: Future[String] = cache.get()
  *   }}}
  */
class AsroeCache[+T](
    val expirationMargin: Long,
    fetch: ExecutionContext ?=> Future[Expirable[T]],
    afterFetchCallback: PartialFunction[Try[Expirable[T]], Any] = {
      (_: Try[Expirable[T]]) => ()
    },
    onBackgroundError: Throwable => Unit = { _ => () }
)(using clock: Clock) {

  /** Tracks ongoing refresh operations to prevent duplicate fetches */
  private val ongoingRefresh
      : AtomicReference[Option[Future[Expirable[T @uncheckedVariance]]]] =
    AtomicReference(None)

  /** Currently cached expirable value, if any */
  @volatile private var expirable: Option[Expirable[T @uncheckedVariance]] =
    None

  /** Manually mark as expired the cached element, if any */
  def expire(): Unit = expirable = None

  /** Gets the cached value.
    *
    * Behavior depends on the current state:
    *   - Fresh data: returns immediately
    *   - Stale data: returns stale value immediately + triggers background
    *     refresh
    *   - No/expired data: waits for fresh data to be fetched
    *
    * @param ec
    *   execution context for async operations
    * @return
    *   Future containing the cached value
    */
  def get()(using ec: ExecutionContext): Future[T] =
    getWithMetadata().map(_.value)

  /** Gets the cached expirable (value and expiration info).
    *
    * Same behavior as get() but returns the full Expirable wrapper.
    *
    * @param ec
    *   execution context for async operations
    * @return
    *   Future containing the cached Expirable
    */
  private def getWithMetadata()(using
      ec: ExecutionContext
  ): Future[Expirable[T]] = {
    getCacheResult() match {
      case CacheResult.Fresh(expirable) =>
        Future.successful(expirable)
      case CacheResult.Stale(expirable, futureExpirable) =>
        futureExpirable.failed.foreach(onBackgroundError)
        Future.successful(expirable)
      case CacheResult.Expired(futureExpirable) =>
        futureExpirable
      case CacheResult.Missing(futureExpirable) =>
        futureExpirable
    }
  }

  /** Determines the current cache state and initiates refresh if needed.
    *
    * Evaluates the cached data against expiration thresholds and returns the
    * appropriate CacheResult indicating whether data is fresh, stale, expired,
    * or missing.
    *
    * @param ec
    *   execution context for async operations
    * @return
    *   CacheResult indicating current state and any ongoing refresh
    */
  private def getCacheResult()(using ec: ExecutionContext): CacheResult[T] = {
    expirable match {
      case None =>
        CacheResult.Missing(
          fetchIfNecessary()
        )
      case Some(expirable) if isFresh(expirable) =>
        CacheResult.Fresh(expirable)
      case Some(expirable) if !isExpired(expirable) =>
        CacheResult.Stale(
          expirable,
          fetchIfNecessary()
        )
      case _ =>
        CacheResult.Expired(
          fetchIfNecessary()
        )
    }
  }

  /** Coordinates fetching to ensure only one fetch operation runs at a time.
    *
    * Uses atomic compare-and-set to claim the right to fetch. If another thread
    * is already fetching, it waits for their result instead of starting a
    * duplicate fetch. Includes an optimization to avoid unnecessary fetches
    * when data becomes fresh between the initial check and fetch coordination.
    *
    * **Failure Recovery**: When threads piggyback on a fetch that fails, they
    * automatically attempt a new fetch rather than propagating the failure. The
    * CAS mechanism ensures only one thread wins the retry, while others
    * piggyback on this new attempt. The original failure is still visible to
    * the thread that initiated it and will trigger appropriate error callbacks.
    * This design provides automatic resilience against transient failures while
    * maintaining the single-fetch guarantee.
    *
    * @param ec
    *   execution context for async operations
    * @return
    *   Future containing the fetched or existing fresh Expirable
    */
  private def fetchIfNecessary()(using
      ec: ExecutionContext
  ): Future[Expirable[T]] = {
    val promise = Promise[Expirable[T]]()
    if (ongoingRefresh.compareAndSet(None, Some(promise.future))) {
      expirable match {
        case Some(expirable) if isFresh(expirable) =>
          ongoingRefresh.set(None)
          promise.completeWith(Future.successful(expirable)).future
        case _ =>
          val future = doFetch().andThen { _ =>
            ongoingRefresh.set(None)
          }
          future.onComplete(afterFetchCallback)
          promise.completeWith(future).future
      }
    } else {
      ongoingRefresh
        .get()
        .map(ref => ref.recoverWith(_ => getWithMetadata()))
        .getOrElse(
          getWithMetadata()
        )
    }
  }

  /** Performs the actual fetch operation and updates the cached value.
    *
    * Calls the user-provided fetch function and updates the internal cache on
    * successful completion. Validates that fetched data is not already expired
    * and handles exceptions by returning failed futures.
    *
    * @param ec
    *   execution context for the fetch operation
    * @return
    *   Future containing the fetched Expirable, or a failed future on error
    */
  private def doFetch()(using
      ec: ExecutionContext
  ): Future[Expirable[T]] = {
    try {
      fetch(using ec)
        .transform(exp =>
          exp match {
            case Success(value) if isExpired(value) =>
              Failure(ElementAlreadyExpired())
            case _ => exp
          }
        )
        .andThen { case Success(expirable) =>
          this.expirable = Some(expirable)
        }
    } catch {
      case e: Exception => Future.failed(e)
    }
  }

  /** Checks if the expirable data is still fresh (not yet in the stale window).
    *
    * Data is considered fresh when the current time is before the expiration
    * time minus the configured expiration margin.
    *
    * @param expirable
    *   the data to check for freshness
    * @return
    *   true if the data is still fresh, false if stale or expired
    */
  private def isFresh(expirable: Expirable[?]): Boolean =
    clock.now() < expirable.expireAt - expirationMargin

  /** Checks if the expirable data is completely expired (past its expiration
    * time).
    *
    * Data is considered expired when the current time equals or exceeds the
    * expiration time, regardless of the expiration margin.
    *
    * @param expirable
    *   the data to check for expiration
    * @return
    *   true if the data is expired, false otherwise
    */
  private def isExpired(expirable: Expirable[?]): Boolean =
    clock.now() >= expirable.expireAt

}
