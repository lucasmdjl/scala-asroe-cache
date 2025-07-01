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

/** Represents a value with an expiration time.
  *
  * @tparam T
  *   the type of the wrapped value
  */
trait Expirable[+T] {

  /** The wrapped value */
  def value: T

  /** Expiration time in milliseconds since Unix epoch */
  def expireAt: Long
}

/** Simple implementation of Expirable that wraps a value with its expiration
  * time.
  *
  * @param value
  *   the value to wrap
  * @param expireAt
  *   expiration time in milliseconds since Unix epoch
  * @tparam T
  *   the type of the wrapped value
  */
case class ExpirableBox[+T](value: T, expireAt: Long) extends Expirable[T]
