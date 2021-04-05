/*
 * Copyright 2019-2021 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package vulcan

/**
  * `Throwable` representation of an [[AvroError]],
  * created with [[AvroError#throwable]].
  */
sealed abstract class AvroException(val message: String) extends RuntimeException(message)

private[vulcan] object AvroException {
  final def apply(message: String): AvroException =
    new AvroException(message) {
      override final def toString: String =
        s"vulcan.AvroException: $getMessage"
    }
}
