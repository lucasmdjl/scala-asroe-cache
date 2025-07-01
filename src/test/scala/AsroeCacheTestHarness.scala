package dev.lucasmdjl.scala.asroecache

import dev.lucasmdjl.scala.clock.ManualClock

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{
  CountDownLatch,
  Executors,
  TimeUnit,
  TimeoutException
}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class CustomException extends RuntimeException

enum CacheState:
  case Fresh
  case Stale
  case Expired
  case Missing

class OpaqueValue

case class TestResult(
    results: List[Try[OpaqueValue]],
    observedFetches: Int,
    observedAfterFetchCallbacks: Int,
    observedBackgroundErrors: Int,
    expectedFetches: Int,
    expectedBackgroundErrors: Int,
    initialCacheState: CacheState,
    oldValue: OpaqueValue,
    newValue: OpaqueValue
)

abstract sealed class TestHarness(
    expiration: Long,
    expirationMargin: Long,
    failedThreads: Int,
    successThreads: Int
) {

  protected val threads: Int = failedThreads + successThreads
  protected val testClock = ManualClock()
  protected val initialValue = OpaqueValue()
  protected val cache: AsroeCache[OpaqueValue] =
    AsroeCache(
      expirationMargin,
      fetchFuture,
      afterFetchCallback = _ =>
        afterFetchCounter.incrementAndGet()
        afterFetchLatch.countDown()
      ,
      onBackgroundError = _ =>
        backgroundErrorCounter.incrementAndGet()
        backgroundErrorLatch.countDown()
    )(using testClock)
  private val threadPool = Executors.newFixedThreadPool(threads + 1)
  private val fetchCounter = AtomicInteger(0)
  private val afterFetchCounter = AtomicInteger(0)
  private val backgroundErrorCounter = AtomicInteger(0)

  given ExecutionContext = ExecutionContext.fromExecutor(threadPool)

  def initialize(): Unit

  def run(): TestResult = {
    val futures = (1 to threads).map { i =>
      Future {
        startLatch.countDown()
        startLatch.await()

        val result = cache.get()

        endLatch.countDown()
        Await.result(result, 1.second)
      }
    }

    val results =
      futures
        .map(fut => Try(Await.result(fut, 1.second)))
        .toList

    val afterFetchAwaited = afterFetchLatch.await(1, TimeUnit.SECONDS)
    if (!afterFetchAwaited)
      throw TimeoutException(
        "Too much time waiting for afterFetchLatch calls"
      )
    val fetchCounterValue =
      fetchCounter.get()
    val afterFetchCounterValue =
      afterFetchCounter.get()
    val backgroundErrorAwaited =
      backgroundErrorLatch.await(1, TimeUnit.SECONDS)
    if (!backgroundErrorAwaited)
      throw TimeoutException(
        "Too much time waiting for onBackgroundError calls"
      )
    val backgroundErrorCounterValue = backgroundErrorCounter.get()

    TestResult(
      results = results,
      observedFetches = fetchCounterValue,
      observedAfterFetchCallbacks = afterFetchCounterValue,
      observedBackgroundErrors = backgroundErrorCounterValue,
      expectedFetches = expectedFetches,
      expectedBackgroundErrors = expectedBackgroundErrors,
      initialCacheState = initialCacheState,
      oldValue = initialValue,
      newValue = currentValue
    )

  }

  def verifyNewValuePropagation(): OpaqueValue =
    Await.result(cache.get(), 1.second)

  protected def expectedBackgroundErrors: Int

  protected def expectedFetches: Int

  protected def currentValue: OpaqueValue

  protected def startLatch: CountDownLatch

  protected def afterFetchLatch: CountDownLatch

  protected def endLatch: CountDownLatch

  protected def backgroundErrorLatch: CountDownLatch

  protected def shouldSucceedFetch(attemptNumber: Int): Boolean

  protected def initialCacheState: CacheState

  private def fetchFuture(using
      ExecutionContext
  ): Future[Expirable[OpaqueValue]] = {
    Future {
      val i = fetchCounter.incrementAndGet()
      val result = ExpirableBox(currentValue, testClock.now() + expiration)
      endLatch.await()
      if (shouldSucceedFetch(i)) {
        result
      } else {
        throw CustomException()
      }
    }
  }
}

class PresentTestHarness(
    expiration: Long,
    expirationMargin: Long,
    advanceTime: Long,
    failedThreads: Int,
    successThreads: Int
) extends TestHarness(
      expiration,
      expirationMargin,
      failedThreads,
      successThreads
    ) {
  private val isFresh: Boolean = advanceTime < expiration - expirationMargin
  private val isExpired: Boolean = advanceTime >= expiration
  private val isStale: Boolean = !isFresh && !isExpired
  private val _endLatch = AtomicReference(CountDownLatch(1))
  private val _startLatch = CountDownLatch(threads)
  private val _afterFetchLatch = AtomicReference(CountDownLatch(1))
  private val _backgroundErrorLatch = CountDownLatch(expectedBackgroundErrors)
  @volatile private var _currentValue: OpaqueValue = initialValue

  override def initialize(): Unit = {
    primeCache()

    _currentValue = OpaqueValue()
    testClock.advance(advanceTime)

    _afterFetchLatch.set(CountDownLatch(expectedFetches))
    _endLatch.set(CountDownLatch(threads))
  }

  override protected def expectedFetches: Int =
    if (isFresh) 0 else failedThreads + Math.min(successThreads, 1)

  private def primeCache(): Unit = {
    cache.get()
    endLatch.countDown()

    val afterFetchCalled = afterFetchLatch.await(1, TimeUnit.SECONDS)
    if (!afterFetchCalled)
      throw TimeoutException(
        s"Too much time waiting for afterFetchLatch initial call"
      )
  }

  override protected def endLatch: CountDownLatch = _endLatch.get()

  override protected def afterFetchLatch: CountDownLatch =
    _afterFetchLatch.get()

  override protected def expectedBackgroundErrors: Int =
    if (isStale) failedThreads else 0

  override protected def shouldSucceedFetch(attemptNumber: Int): Boolean = {
    attemptNumber == 1 || attemptNumber > failedThreads + 1
  }

  override protected def initialCacheState: CacheState =
    if (isFresh) CacheState.Fresh
    else if (isExpired) CacheState.Expired
    else CacheState.Stale

  override protected def startLatch: CountDownLatch = _startLatch

  override protected def backgroundErrorLatch: CountDownLatch =
    _backgroundErrorLatch

  override protected def currentValue: OpaqueValue = _currentValue
}

class MissingTestHarness(
    expiration: Long,
    expirationMargin: Long,
    failedThreads: Int,
    successThreads: Int
) extends TestHarness(
      expiration,
      expirationMargin,
      failedThreads,
      successThreads
    ) {
  private val _startLatch = CountDownLatch(threads)
  private val _afterFetchLatch = CountDownLatch(expectedFetches)
  private val _endLatch = CountDownLatch(threads)
  private val _backgroundErrorLatch = CountDownLatch(expectedBackgroundErrors)

  override def initialize(): Unit = {}

  override protected def expectedFetches: Int =
    failedThreads + Math.min(successThreads, 1)

  override protected def expectedBackgroundErrors: Int = 0

  override protected def shouldSucceedFetch(attemptNumber: Int): Boolean =
    attemptNumber > failedThreads

  override protected def initialCacheState: CacheState = CacheState.Missing

  override protected def currentValue: OpaqueValue = initialValue

  override protected def startLatch: CountDownLatch = _startLatch

  override protected def afterFetchLatch: CountDownLatch = _afterFetchLatch

  override protected def endLatch: CountDownLatch = _endLatch

  override protected def backgroundErrorLatch: CountDownLatch =
    _backgroundErrorLatch
}

class ExpireTestHarness(
    expiration: Long,
    expirationMargin: Long,
    advanceTime: Long
) extends PresentTestHarness(
      expiration = expiration,
      expirationMargin = expirationMargin,
      advanceTime = advanceTime,
      failedThreads = 0,
      successThreads = 1
    ) {

  override def initialize(): Unit = {
    super.initialize()
    cache.expire()
  }

  override protected def expectedFetches: Int = 1
}
