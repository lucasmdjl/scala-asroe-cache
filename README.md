# AsroeCache

**A**sync **S**elf-**R**efreshing **O**ne-**E**lement Cache - A Scala 3 micro-library for intelligent caching with background refresh capabilities.

## Overview

AsroeCache solves a common problem in high-performance applications: serving cached data that's "fresh enough" while automatically refreshing stale data in the background. Unlike traditional caches that block on cache misses, AsroeCache can serve slightly stale data while fetching fresh data asynchronously.

## Key Features

- **Non-blocking operations**: Never blocks threads waiting for fresh data when stale data is acceptable
- **Intelligent expiration**: Distinguishes between "stale but usable" and "truly expired" data
- **Background refresh**: Automatically refreshes stale data without blocking callers
- **Thread-safe**: Handles concurrent access safely with minimal contention
- **Single fetch guarantee**: Ensures only one fetch operation runs at a time, even under high concurrency
- **Automatic failure recovery**: Self-healing behavior when fetch operations fail
- **Configurable callbacks**: Extensible lifecycle hooks for monitoring and logging
- **Testable**: Pluggable clock for deterministic testing

## Core Concepts

### Expiration Margin

AsroeCache uses an *expiration margin* to create a window between "fresh" and "expired" data:

```
Timeline: [----fresh----][--stale but usable--][--expired--]
                        ↑                      ↑
                   expire_at - margin      expire_at
```

- **Fresh data**: Served immediately
- **Stale data**: Served immediately + triggers background refresh
- **Expired data**: Blocks until fresh data is fetched

### Failure Recovery

When fetch operations fail, AsroeCache implements intelligent recovery:
- Threads waiting for a failed fetch automatically retry (only one wins the retry)
- The original failure is still visible to the initiating thread
- Background failures are handled via configurable error callbacks
- No thundering herd: the single-fetch guarantee is maintained even during retries

## Basic Usage

### Simple Cache

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

// Create a cache with 5-second expiration margin
val cache = AsroeCache[String](
  expirationMargin = 5000,
  fetch = Future {
    // This could be an HTTP call, database query, etc.
    val data = fetchExpensiveData()
    ExpirableBox(data, System.currentTimeMillis() + 60000) // Expires in 1 minute
  }
)

// Get cached value
val result: Future[String] = cache.get()
```

### Advanced Usage with Callbacks

```scala
val cache = AsroeCache[String](
  expirationMargin = 5000,
  fetch = Future {
    val data = fetchExpensiveData()
    ExpirableBox(data, System.currentTimeMillis() + 60000)
  },
  afterFetchCallback = {
    case Success(expirable) => logger.info(s"Successfully fetched data, expires at ${expirable.expireAt}")
    case Failure(exception) => logger.error(s"Fetch failed: ${exception.getMessage}")
  },
  onBackgroundError = exception => {
    logger.warn(s"Background refresh failed: ${exception.getMessage}")
    metrics.incrementCounter("cache.background.errors")
  }
)
```

## Thread Safety Guarantees

AsroeCache provides strong thread safety guarantees:

1. **Single fetch guarantee**: Only one fetch operation runs at a time, even with many concurrent callers
2. **Non-blocking reads**: Reading cached data never blocks other operations
3. **Atomic updates**: Cache updates are atomic and consistent
4. **Memory safety**: All operations are safe for concurrent access

### Concurrency Behavior

- **Multiple threads requesting fresh data**: All get the same cached value immediately
- **Multiple threads requesting stale data**: All get the stale value immediately, but only one background refresh is triggered
- **Multiple threads requesting expired data**: All wait for the same single fetch operation
- **Fetch failures**: Waiting threads automatically retry while maintaining single-fetch guarantee

## Error Handling

### Fetch Failures

When the fetch operation fails:
- If stale data is available, it continues to be served
- If no data is available, the error is propagated to callers
- Threads waiting for a failed fetch automatically attempt new fetches
- The cache remains in a consistent state and will retry on the next request
- Background failures trigger the `onBackgroundError` callback
- All fetch attempts trigger the `afterFetchCallback` for monitoring

### ElementAlreadyExpired Exception

The cache validates that fetched data is not already expired. If your fetch function returns data that's already past its expiration time, an `ElementAlreadyExpired` exception is thrown. This typically indicates:
- Expiration times set in the past
- Clock skew between systems
- Fetch operations taking longer than data lifetime
- Data source returning pre-expired data

## API Reference

### AsroeCache[+T]

#### Constructor
```scala
class AsroeCache[+T](
  expirationMargin: Long,
  fetch: ExecutionContext ?=> Future[Expirable[T]],
  afterFetchCallback: PartialFunction[Try[Expirable[T]], Any] = { _ => () },
  onBackgroundError: Throwable => Unit = { _ => () }
)(using clock: Clock)
```

- `expirationMargin`: Milliseconds before expiration to trigger background refresh
- `fetch`: Function that returns fresh data wrapped in `Expirable`
- `afterFetchCallback`: Called after each fetch attempt with the result
- `onBackgroundError`: Called when background refresh operations fail
- `clock`: Time source

#### Methods
```scala
def get()(using ExecutionContext): Future[T]
```

### Expirable[+T]

```scala
trait Expirable[+T] {
  def value: T
  def expireAt: Long  // Unix timestamp in milliseconds
}

case class ExpirableBox[+T](value: T, expireAt: Long) extends Expirable[T]
```

## Performance Characteristics

- **Memory overhead**: Minimal - stores only one cached value plus small metadata
- **Latency**: Sub-microsecond for cache hits, zero blocking for stale-but-usable data
- **Throughput**: Scales linearly with CPU cores for cache hits
- **Fetch coordination**: Uses lock-free algorithms for coordinating single fetch operations
- **Failure resilience**: Automatic recovery without performance degradation

## Use Cases

AsroeCache is ideal for:

- **API tokens**: OAuth access tokens, refresh tokens, API keys with automatic refresh
- **Configuration data**: Settings that change infrequently but must be reasonably fresh
- **Computed values**: Expensive calculations that can tolerate brief staleness
- **External service calls**: Results from slow or rate-limited APIs
- **Database lookups**: Reference data that changes occasionally
- **Circuit breaker state**: Cached health checks with automatic refresh

## Comparison with Other Caches

| Feature | AsroeCache | Caffeine | Simple @volatile var |
|---------|------------|----------|---------------------|
| Background refresh | ✅ | ✅ | ❌ |
| Non-blocking stale reads | ✅ | ❌ | ✅ |
| Single fetch guarantee | ✅ | ✅ | ❌ |
| Automatic failure recovery | ✅ | ❌ | ❌ |
| Lifecycle callbacks | ✅ | ✅ | ❌ |
| Memory overhead | Minimal | High | Minimal |
| Complex eviction policies | ❌ | ✅ | ❌ |
| Multiple keys | ❌ | ✅ | ❌ |

## Requirements

- Scala 3.0+
- [scala-clock](https://github.com/lucasmdjl/scala-clock)

## License

AGPL-3.0
