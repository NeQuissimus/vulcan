/*
 * Copyright 2019 OVO Energy Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vulcan

import cats.{~>, Invariant, Show}
import cats.data.{Chain, NonEmptyChain, NonEmptyList, NonEmptySet, NonEmptyVector}
import cats.free.FreeApplicative
import cats.implicits._
import java.nio.ByteBuffer
import java.time.{Instant, LocalDate}
import java.util.UUID
import org.apache.avro.{Conversions, LogicalTypes, Schema, SchemaBuilder}
import org.apache.avro.generic._
import org.apache.avro.util.Utf8
import scala.collection.immutable.SortedSet
import scala.reflect.runtime.universe.WeakTypeTag
import vulcan.internal.converters.collection._

/**
  * Provides a schema, along with encoding and decoding functions
  * for a given type.
  */
sealed abstract class Codec[A] {

  /** The schema or an error if the schema could not be generated. */
  def schema: Either[AvroError, Schema]

  /** Attempts to encode the specified value using the provided schema. */
  def encode(a: A, schema: Schema): Either[AvroError, Any]

  /** Attempts to decode the specified value using the provided schema. */
  def decode(value: Any, schema: Schema): Either[AvroError, A]

  /**
    * Returns a new [[Codec]] which uses this [[Codec]]
    * for encoding and decoding, mapping back-and-forth
    * between types `A` and `B`.
    */
  final def imap[B](f: A => B)(g: B => A): Codec[B] =
    Codec.instance(
      schema,
      (b, schema) => encode(g(b), schema),
      (a, schema) => decode(a, schema).map(f)
    )

  /**
    * Returns a new [[Codec]] which uses this [[Codec]]
    * for encoding and decoding, mapping back-and-forth
    * between types `A` and `B`.
    *
    * Similar to [[Codec#imap]], except the mapping from
    * `A` to `B` might be unsuccessful.
    */
  final def imapError[B](f: A => Either[AvroError, B])(g: B => A): Codec[B] =
    Codec.instance(
      schema,
      (b, schema) => encode(g(b), schema),
      (a, schema) => decode(a, schema).flatMap(f)
    )
}

/**
  * @groupname General General Codecs
  * @groupprio General 0
  * @groupdesc General Default codecs for standard library types.
  *
  * @groupname Collection Collection Codecs
  * @groupprio Collection 1
  * @groupdesc Collection Default codecs for standard library collection types.
  *
  * @groupname Cats Cats Codecs
  * @groupprio Cats 2
  * @groupdesc Cats Default codecs for Cats data types and type class instances for [[Codec]].
  *
  * @groupname JavaTime Java Time Codecs
  * @groupprio JavaTime 3
  * @groupdesc JavaTime Default codecs for `java.time` types.
  *
  * @groupname JavaUtil Java Util Codecs
  * @groupprio JavaUtil 4
  * @groupdesc JavaUtil Default codecs for `java.util` types.
  *
  * @groupname Create Create Codecs
  * @groupprio Create 5
  * @groupdesc Create Functions for creating new codecs.
  *
  * @groupname Derive Derive Codecs
  * @groupprio Derive 6
  * @groupdesc Derive Functions for deriving new codecs.
  *
  * @groupname Utilities
  * @groupprio Utilities 7
  * @groupdesc Utilities Miscellaneous utility functions.
  */
final object Codec {

  /**
    * Returns the [[Codec]] for the specified type.
    *
    * @group Utilities
    */
  final def apply[A](implicit codec: Codec[A]): Codec[A] =
    codec

  /**
    * @group General
    */
  implicit final val boolean: Codec[Boolean] =
    Codec.instance(
      Right(SchemaBuilder.builder().booleanType()),
      (boolean, schema) => {
        schema.getType() match {
          case Schema.Type.BOOLEAN =>
            Right(java.lang.Boolean.valueOf(boolean))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Boolean",
                  schemaType,
                  NonEmptyList.of(Schema.Type.BOOLEAN)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.BOOLEAN =>
            value match {
              case boolean: java.lang.Boolean =>
                Right(boolean)
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Boolean", "Boolean"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Boolean",
                  schemaType,
                  NonEmptyList.of(Schema.Type.BOOLEAN)
                )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val byte: Codec[Byte] =
    Codec.instance(
      Right(SchemaBuilder.builder().intType()),
      (byte, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            Right(java.lang.Integer.valueOf(byte.toInt))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Byte",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      }, {
        val min: Int = Byte.MinValue.toInt
        val max: Int = Byte.MaxValue.toInt
        (value, schema) => {
          schema.getType() match {
            case Schema.Type.INT =>
              value match {
                case integer: java.lang.Integer =>
                  if (min <= integer && integer <= max)
                    Right(integer.toByte)
                  else Left(AvroError.unexpectedByte(integer))

                case other =>
                  Left(AvroError.decodeUnexpectedType(other, "Int", "Byte"))

              }

            case schemaType =>
              Left {
                AvroError
                  .decodeUnexpectedSchemaType(
                    "Byte",
                    schemaType,
                    NonEmptyList.of(Schema.Type.INT)
                  )
              }
          }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val bytes: Codec[Array[Byte]] =
    Codec.instance(
      Right(SchemaBuilder.builder().bytesType()),
      (bytes, schema) => {
        schema.getType() match {
          case Schema.Type.BYTES =>
            Right(ByteBuffer.wrap(bytes))

          case Schema.Type.FIXED =>
            val length = bytes.length
            val fixedSize = schema.getFixedSize()
            if (length <= fixedSize) {
              Right {
                val buffer = ByteBuffer.allocate(fixedSize).put(bytes)
                GenericData.get().createFixed(null, buffer.array(), schema)
              }
            } else {
              Left(AvroError.encodeExceedsFixedSize(length, fixedSize))
            }

          case schemaType =>
            val expectedTypes = NonEmptyList.of(Schema.Type.BYTES, Schema.Type.FIXED)
            Left(AvroError.encodeUnexpectedSchemaType("Array[Byte]", schemaType, expectedTypes))
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.BYTES =>
            value match {
              case buffer: ByteBuffer =>
                Right(buffer.array())
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "ByteBuffer", "Array[Byte]"))
            }

          case Schema.Type.FIXED =>
            value match {
              case fixed: GenericFixed =>
                val bytes = fixed.bytes()
                if (bytes.length <= schema.getFixedSize()) Right(bytes)
                else Left(AvroError.decodeExceedsFixedSize(bytes.length, schema.getFixedSize()))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "GenericFixed", "Array[Byte]"))
            }

          case schemaType =>
            val expectedTypes = NonEmptyList.of(Schema.Type.BYTES, Schema.Type.FIXED)
            Left(AvroError.decodeUnexpectedSchemaType("Array[Byte]", schemaType, expectedTypes))
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final def chain[A](implicit codec: Codec[A]): Codec[Chain[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (chain, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            chain.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Chain",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList
                  .traverse(codec.decode(_, elementType))
                  .map(Chain.fromSeq)

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "Chain"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Chain",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val char: Codec[Char] =
    Codec.instance(
      Right(SchemaBuilder.builder().stringType()),
      (char, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            Right(new Utf8(char.toString))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Char",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            value match {
              case utf8: Utf8 =>
                val string = utf8.toString
                if (string.length == 1) Right(string.charAt(0))
                else Left(AvroError.unexpectedChar(string.length))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Utf8", "Char"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Char",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      }
    )

  /**
    * Returns a new decimal [[Codec]] for type `BigDecimal`.
    *
    * @group Create
    */
  final def decimal(
    precision: Int,
    scale: Int
  ): Codec[BigDecimal] = {
    val conversion = new Conversions.DecimalConversion()
    Codec.instance(
      AvroError.catchNonFatal {
        Right {
          LogicalTypes
            .decimal(precision, scale)
            .addToSchema(SchemaBuilder.builder().bytesType())
        }
      },
      (bigDecimal, schema) => {
        schema.getType() match {
          case Schema.Type.BYTES =>
            schema.getLogicalType() match {
              case decimal: LogicalTypes.Decimal =>
                if (bigDecimal.scale == decimal.getScale()) {
                  if (bigDecimal.precision <= decimal.getPrecision()) {
                    Right(conversion.toBytes(bigDecimal.underlying(), schema, decimal))
                  } else {
                    Left {
                      AvroError
                        .encodeDecimalPrecisionExceeded(
                          bigDecimal.precision,
                          decimal.getPrecision()
                        )
                    }
                  }
                } else
                  Left(AvroError.encodeDecimalScalesMismatch(bigDecimal.scale, decimal.getScale()))

              case logicalType =>
                Left(AvroError.encodeUnexpectedLogicalType(logicalType, "BigDecimal"))
            }

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "BigDecimal",
                  schemaType,
                  NonEmptyList.of(Schema.Type.BYTES)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.BYTES =>
            schema.getLogicalType() match {
              case decimal: LogicalTypes.Decimal =>
                value match {
                  case buffer: ByteBuffer =>
                    val bigDecimal = BigDecimal(conversion.fromBytes(buffer, schema, decimal))
                    if (bigDecimal.precision <= decimal.getPrecision()) {
                      Right(bigDecimal)
                    } else {
                      Left {
                        AvroError
                          .decodeDecimalPrecisionExceeded(
                            bigDecimal.precision,
                            decimal.getPrecision()
                          )
                      }
                    }

                  case other =>
                    Left(AvroError.decodeUnexpectedType(other, "ByteBuffer", "BigDecimal"))
                }

              case logicalType =>
                Left(AvroError.decodeUnexpectedLogicalType(logicalType, "BigDecimal"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "BigDecimal",
                  schemaType,
                  NonEmptyList.of(Schema.Type.BYTES)
                )
            }
        }
      }
    )
  }

  /**
    * Returns the result of decoding the specified value
    * to the specified type.
    *
    * @group Utilities
    */
  final def decode[A](value: Any)(implicit codec: Codec[A]): Either[AvroError, A] =
    codec.schema.flatMap(codec.decode(value, _))

  /**
    * Returns an enum [[Codec]] for type `A`, deriving details
    * like the name, namespace, and [[AvroDoc]] documentation
    * from the type `A` using type tags.
    *
    * @group Derive
    */
  final def deriveEnum[A](
    symbols: Seq[String],
    encode: A => String,
    decode: String => Either[AvroError, A]
  )(implicit tag: WeakTypeTag[A]): Codec[A] = {
    val name =
      tag.tpe.typeSymbol.name.decodedName.toString

    val namespace =
      tag.tpe.typeSymbol.annotations
        .collectFirst {
          case annotation if annotation.tree.tpe.typeSymbol.fullName == "vulcan.AvroNamespace" =>
            val namespace = annotation.tree.children.last.toString
            namespace.substring(1, namespace.length - 1)
        }
        .getOrElse {
          tag.tpe.typeSymbol.fullName.dropRight(name.length + 1)
        }

    val doc =
      tag.tpe.typeSymbol.annotations.collectFirst {
        case annotation if annotation.tree.tpe.typeSymbol.fullName == "vulcan.AvroDoc" =>
          val doc = annotation.tree.children.last.toString
          doc.substring(1, doc.length - 1)
      }

    Codec.enum(
      name = name,
      symbols = symbols,
      encode = encode,
      decode = decode,
      namespace = Some(namespace),
      aliases = Nil,
      doc = doc,
      default = None
    )
  }

  /**
    * @group General
    */
  implicit final val double: Codec[Double] =
    Codec.instance(
      Right(SchemaBuilder.builder().doubleType()),
      (double, schema) => {
        schema.getType() match {
          case Schema.Type.DOUBLE =>
            Right(java.lang.Double.valueOf(double))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Double",
                  schemaType,
                  NonEmptyList.of(Schema.Type.DOUBLE)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.DOUBLE =>
            value match {
              case double: java.lang.Double =>
                Right(double)
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Double", "Double"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Double",
                  schemaType,
                  NonEmptyList.of(Schema.Type.DOUBLE)
                )
            }
        }
      }
    )

  /**
    * Returns the result of encoding the specified value.
    *
    * @group Utilities
    */
  final def encode[A](a: A)(implicit codec: Codec[A]): Either[AvroError, Any] =
    codec.schema.flatMap(codec.encode(a, _))

  /**
    * Returns a new enum [[Codec]] for type `A`.
    *
    * @group Create
    */
  final def enum[A](
    name: String,
    symbols: Seq[String],
    encode: A => String,
    decode: String => Either[AvroError, A],
    namespace: Option[String] = None,
    aliases: Seq[String] = Seq.empty,
    doc: Option[String] = None,
    default: Option[A] = None
  ): Codec[A] = {
    val typeName = namespace.fold(name)(namespace => s"$namespace.$name")
    Codec.instance(
      AvroError.catchNonFatal {
        val schema =
          Schema.createEnum(
            name,
            doc.orNull,
            namespace.orNull,
            symbols.asJava,
            default.map(encode).orNull
          )

        aliases.foreach(schema.addAlias)

        Right(schema)
      },
      (a, schema) => {
        schema.getType() match {
          case Schema.Type.ENUM =>
            if (schema.getFullName() == typeName) {
              val symbols = schema.getEnumSymbols().asScala.toList
              val symbol = encode(a)

              if (symbols.contains(symbol))
                Right(GenericData.get().createEnum(symbol, schema))
              else
                Left(AvroError.encodeSymbolNotInSchema(symbol, symbols, typeName))
            } else Left(AvroError.encodeNameMismatch(schema.getFullName(), typeName))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.ENUM)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ENUM =>
            value match {
              case enum: GenericEnumSymbol[_] =>
                val fullName = schema.getFullName()
                if (fullName == typeName) {
                  val symbols = schema.getEnumSymbols().asScala.toList
                  val symbol = enum.toString()

                  if (symbols.contains(symbol))
                    decode(symbol)
                  else
                    Left(AvroError.decodeSymbolNotInSchema(symbol, symbols, typeName))
                } else Left(AvroError.decodeNameMismatch(schema.getFullName(), typeName))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "GenericEnumSymbol", typeName))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.ENUM)
                )
            }
        }
      }
    )
  }

  /**
    * @group General
    */
  implicit final val float: Codec[Float] =
    Codec.instance(
      Right(SchemaBuilder.builder().floatType()),
      (float, schema) => {
        schema.getType() match {
          case Schema.Type.FLOAT =>
            Right(java.lang.Float.valueOf(float))
          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Float",
                  schemaType,
                  NonEmptyList.of(Schema.Type.FLOAT)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.FLOAT =>
            value match {
              case float: java.lang.Float =>
                Right(float)
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Float", "Float"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Float",
                  schemaType,
                  NonEmptyList.of(Schema.Type.FLOAT)
                )
            }
        }
      }
    )

  /**
    * Returns a new [[Codec]] instance using the specified
    * `Schema`, and encode and decode functions.
    *
    * @group Create
    */
  final def instance[A](
    schema: Either[AvroError, Schema],
    encode: (A, Schema) => Either[AvroError, Any],
    decode: (Any, Schema) => Either[AvroError, A]
  ): Codec[A] = {
    val _schema = schema
    val _encode = encode
    val _decode = decode

    new Codec[A] {
      override final val schema: Either[AvroError, Schema] =
        _schema

      override final def encode(a: A, schema: Schema): Either[AvroError, Any] =
        _encode(a, schema)

      override final def decode(value: Any, schema: Schema): Either[AvroError, A] =
        _decode(value, schema)

      override final def toString: String =
        schema match {
          case Right(schema) => s"Codec(${schema.toString(true)})"
          case Left(error)   => error.toString()
        }
    }
  }

  /**
    * @group JavaTime
    */
  implicit final val instant: Codec[Instant] =
    Codec.instance(
      Right(LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder().longType())),
      (instant, schema) => {
        schema.getType() match {
          case Schema.Type.LONG =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.timestampMillis())
              Right(java.lang.Long.valueOf(instant.toEpochMilli()))
            else Left(AvroError.encodeUnexpectedLogicalType(logicalType, "Instant"))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Instant",
                  schemaType,
                  NonEmptyList.of(Schema.Type.LONG)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.LONG =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.timestampMillis()) {
              value match {
                case long: java.lang.Long =>
                  Right(Instant.ofEpochMilli(long))
                case other =>
                  Left(AvroError.decodeUnexpectedType(other, "Long", "Instant"))
              }
            } else Left(AvroError.decodeUnexpectedLogicalType(logicalType, "Instant"))

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Instant",
                  schemaType,
                  NonEmptyList.of(Schema.Type.LONG)
                )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val int: Codec[Int] =
    Codec.instance(
      Right(SchemaBuilder.builder().intType()),
      (int, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            Right(java.lang.Integer.valueOf(int))
          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Int",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            value match {
              case integer: java.lang.Integer =>
                Right(integer)
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Int", "Int"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Int",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      }
    )

  /**
    * @group Collection
    */
  implicit final def list[A](implicit codec: Codec[A]): Codec[List[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (list, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            list.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "List",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList.traverse(codec.decode(_, elementType))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "List"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "List",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group JavaTime
    */
  implicit final val localDate: Codec[LocalDate] =
    Codec.instance(
      Right(LogicalTypes.date().addToSchema(SchemaBuilder.builder().intType())),
      (localDate, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.date()) {
              Right(java.lang.Integer.valueOf(localDate.toEpochDay.toInt))
            } else Left(AvroError.encodeUnexpectedLogicalType(logicalType, "LocalDate"))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "LocalDate",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.date()) {
              value match {
                case int: java.lang.Integer =>
                  Right(LocalDate.ofEpochDay(int.toLong))
                case other =>
                  Left(AvroError.decodeUnexpectedType(other, "Integer", "LocalDate"))
              }
            } else Left(AvroError.decodeUnexpectedLogicalType(logicalType, "LocalDate"))
          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "LocalDate",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val long: Codec[Long] =
    Codec.instance(
      Right(SchemaBuilder.builder().longType()),
      (long, schema) => {
        schema.getType() match {
          case Schema.Type.LONG =>
            Right(java.lang.Long.valueOf(long))
          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Long",
                  schemaType,
                  NonEmptyList.of(Schema.Type.LONG)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.LONG =>
            value match {
              case long: java.lang.Long =>
                Right(long)
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Long", "Long"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Long",
                  schemaType,
                  NonEmptyList.of(Schema.Type.LONG)
                )
            }
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final def nonEmptyChain[A](implicit codec: Codec[A]): Codec[NonEmptyChain[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (chain, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            chain.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "NonEmptyChain",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList
                  .traverse(codec.decode(_, elementType))
                  .flatMap { list =>
                    if (list.isEmpty) Left(AvroError.decodeEmptyCollection("NonEmptyChain"))
                    else Right(NonEmptyChain.fromChainUnsafe(Chain.fromSeq(list)))
                  }

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "NonEmptyChain"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "NonEmptyChain",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final def nonEmptyList[A](implicit codec: Codec[A]): Codec[NonEmptyList[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (list, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            list.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "NonEmptyList",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList
                  .traverse(codec.decode(_, elementType))
                  .flatMap { list =>
                    if (list.isEmpty) Left(AvroError.decodeEmptyCollection("NonEmptyList"))
                    else Right(NonEmptyList.fromListUnsafe(list))
                  }

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "NonEmptyList"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "NonEmptyList",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final def nonEmptySet[A](
    implicit codec: Codec[A],
    ordering: Ordering[A]
  ): Codec[NonEmptySet[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (set, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            set.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "NonEmptySet",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList
                  .traverse(codec.decode(_, elementType))
                  .flatMap { list =>
                    if (list.isEmpty) Left(AvroError.decodeEmptyCollection("NonEmptySet"))
                    else Right(NonEmptySet.fromSetUnsafe(SortedSet(list: _*)))
                  }

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "NonEmptySet"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "NonEmptySet",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final def nonEmptyVector[A](implicit codec: Codec[A]): Codec[NonEmptyVector[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (vector, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            vector.toVector.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError.encodeUnexpectedSchemaType(
                "NonEmptyVector",
                schemaType,
                NonEmptyList.of(Schema.Type.ARRAY)
              )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toVector
                  .traverse(codec.decode(_, elementType))
                  .flatMap { vector =>
                    if (vector.isEmpty) Left(AvroError.decodeEmptyCollection("NonEmptyVector"))
                    else Right(NonEmptyVector.fromVectorUnsafe(vector))
                  }

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "NonEmptyVector"))
            }

          case schemaType =>
            Left {
              AvroError.decodeUnexpectedSchemaType(
                "NonEmptyVector",
                schemaType,
                NonEmptyList.of(Schema.Type.ARRAY)
              )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final def option[A](implicit codec: Codec[A]): Codec[Option[A]] =
    Codec.instance(
      AvroError.catchNonFatal {
        codec.schema.map { schema =>
          val nullSchema = SchemaBuilder.builder().nullType()
          val schemas = List(nullSchema, schema).asJava
          Schema.createUnion(schemas)
        }
      },
      (option, schema) => {
        schema.getType() match {
          case Schema.Type.UNION =>
            val schemas =
              schema.getTypes()

            val nonNullSchema =
              if (schemas.size == 2) {
                if (schemas.get(0).getType() == Schema.Type.NULL)
                  Right(schemas.get(1))
                else Right(schemas.get(0))
              } else Left(AvroError.encodeUnexpectedOptionSchema(schema))

            nonNullSchema.flatMap { schema =>
              option match {
                case Some(a) => codec.encode(a, schema)
                case None    => Right(null)
              }
            }

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Option",
                  schemaType,
                  NonEmptyList.of(Schema.Type.UNION)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.UNION =>
            val schemas = schema.getTypes()

            val nonNullSchema =
              if (schemas.size == 2) {
                if (schemas.get(0).getType() == Schema.Type.NULL)
                  Right(schemas.get(1))
                else Right(schemas.get(0))
              } else Left(AvroError.decodeUnexpectedOptionSchema(schema))

            nonNullSchema.flatMap { schema =>
              value match {
                case null  => Right(None)
                case other => codec.decode(other, schema).map(Some(_))
              }
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Option",
                  schemaType,
                  NonEmptyList.of(Schema.Type.UNION)
                )
            }
        }
      }
    )

  /**
    * Returns a new record [[Codec]] for type `A`.
    *
    * @group Create
    */
  final def record[A](
    name: String,
    namespace: Option[String] = None,
    doc: Option[String] = None,
    aliases: Seq[String] = Seq.empty,
    props: Seq[(String, String)] = Seq.empty
  )(f: FieldBuilder[A] => FreeApplicative[Field[A, ?], A]): Codec[A] = {
    val typeName = namespace.fold(name)(namespace => s"$namespace.$name")
    val free = f(FieldBuilder.instance)
    Codec.instance(
      AvroError.catchNonFatal {
        val fields =
          free.analyze {
            λ[Field[A, ?] ~> λ[a => Either[AvroError, Chain[Schema.Field]]]] {
              field =>
                field.codec.schema.flatMap {
                  schema =>
                    field.default
                      .traverse(field.codec.encode(_, schema))
                      .map {
                        default =>
                          Chain.one {
                            val schemaField =
                              new Schema.Field(
                                field.name,
                                schema,
                                field.doc.orNull,
                                default.map {
                                  case null  => Schema.Field.NULL_DEFAULT_VALUE
                                  case other => other
                                }.orNull,
                                field.order.getOrElse(Schema.Field.Order.ASCENDING)
                              )

                            field.aliases.foreach(schemaField.addAlias)

                            field.props.foreach {
                              case (name, value) =>
                                schemaField.addProp(name, value)
                            }

                            schemaField
                          }
                      }
                }
            }
          }

        fields.map { fields =>
          val record =
            Schema.createRecord(
              name,
              doc.orNull,
              namespace.orNull,
              false,
              fields.toList.asJava
            )

          aliases.foreach(record.addAlias)

          props.foreach {
            case (name, value) =>
              record.addProp(name, value)
          }

          record
        }
      },
      (a, schema) => {
        schema.getType() match {
          case Schema.Type.RECORD =>
            if (schema.getFullName() == typeName) {
              val schemaFields =
                schema.getFields().asScala

              val fields =
                free.analyze {
                  λ[Field[A, ?] ~> λ[a => Either[AvroError, Chain[(Any, Int)]]]] { field =>
                    schemaFields
                      .collectFirst {
                        case schemaField if schemaField.name == field.name =>
                          field.codec
                            .encode(field.access(a), schemaField.schema)
                            .map(result => Chain.one((result, schemaField.pos())))
                      }
                      .getOrElse(Left(AvroError.encodeMissingRecordField(field.name, typeName)))
                  }
                }

              fields.map { values =>
                val record = new GenericData.Record(schema)
                values.foldLeft(()) {
                  case ((), (value, pos)) =>
                    record.put(pos, value)
                }

                record
              }
            } else Left(AvroError.encodeNameMismatch(schema.getFullName(), typeName))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.RECORD)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.RECORD =>
            value match {
              case record: IndexedRecord =>
                val recordSchema = record.getSchema()
                if (recordSchema.getFullName() == typeName) {
                  val recordFields = recordSchema.getFields()

                  free.foldMap {
                    λ[Field[A, ?] ~> Either[AvroError, ?]] { field =>
                      val schemaField = recordSchema.getField(field.name)
                      if (schemaField != null) {
                        val value = record.get(recordFields.indexOf(schemaField))
                        field.codec.decode(value, schemaField.schema())
                      } else {
                        field.default.toRight {
                          AvroError.decodeMissingRecordField(field.name, typeName)
                        }
                      }
                    }
                  }
                } else
                  Left(AvroError.decodeUnexpectedRecordName(recordSchema.getFullName(), typeName))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "IndexedRecord", typeName))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.RECORD)
                )
            }
        }
      }
    )
  }

  /**
    * @group Collection
    */
  implicit final def seq[A](implicit codec: Codec[A]): Codec[Seq[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (seq, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            seq.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Seq",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList.traverse(codec.decode(_, elementType))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "Seq"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Seq",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group Collection
    */
  implicit final def set[A](implicit codec: Codec[A]): Codec[Set[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (set, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            set.toList.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Set",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toList.traverse(codec.decode(_, elementType)).map(_.toSet)

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "Set"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Set",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val short: Codec[Short] =
    Codec.instance(
      Right(SchemaBuilder.builder().intType()),
      (short, schema) => {
        schema.getType() match {
          case Schema.Type.INT =>
            Right(java.lang.Integer.valueOf(short.toInt))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Short",
                  schemaType,
                  NonEmptyList.of(Schema.Type.INT)
                )
            }
        }
      }, {
        val min: Int = Short.MinValue.toInt
        val max: Int = Short.MaxValue.toInt
        (value, schema) => {
          schema.getType() match {
            case Schema.Type.INT =>
              value match {
                case integer: java.lang.Integer =>
                  if (min <= integer && integer <= max)
                    Right(integer.toShort)
                  else Left(AvroError.unexpectedShort(integer))

                case other =>
                  Left(AvroError.decodeUnexpectedType(other, "Int", "Short"))
              }

            case schemaType =>
              Left {
                AvroError
                  .decodeUnexpectedSchemaType(
                    "Short",
                    schemaType,
                    NonEmptyList.of(Schema.Type.INT)
                  )
              }
          }
        }
      }
    )

  /**
    * @group General
    */
  implicit final val string: Codec[String] =
    Codec.instance(
      Right(SchemaBuilder.builder().stringType()),
      (string, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            Right(new Utf8(string))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "String",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            value match {
              case utf8: Utf8 =>
                Right(utf8.toString())
              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Utf8", "String"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "String",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      }
    )

  /**
    * Returns a new union [[Codec]] for type `A`.
    *
    * @group Create
    */
  final def union[A](f: AltBuilder[A] => Chain[Alt[A]])(
    implicit tag: WeakTypeTag[A]
  ): Codec[A] = {
    val typeName = tag.tpe.typeSymbol.fullName
    val alts = f(AltBuilder.instance)
    Codec.instance(
      AvroError.catchNonFatal {
        alts.toList
          .traverse(_.codec.schema)
          .map(schemas => Schema.createUnion(schemas.asJava))
      },
      (a, schema) => {
        schema.getType() match {
          case Schema.Type.UNION =>
            alts
              .foldMapK { alt =>
                alt.prism.getOption(a).map { b =>
                  alt.codec.schema.flatMap { altSchema =>
                    val altName =
                      altSchema.getFullName

                    val altUnionSchema =
                      schema.getTypes.asScala
                        .find(_.getFullName == altName)
                        .toRight(AvroError.encodeMissingUnionSchema(altName, typeName))

                    altUnionSchema.flatMap(alt.codec.encode(b, _))
                  }
                }
              }
              .getOrElse {
                Left(AvroError.encodeExhaustedAlternatives(a, typeName))
              }

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.UNION)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.UNION =>
            value match {
              case container: GenericContainer =>
                val altName =
                  container.getSchema.getFullName

                val altUnionSchema =
                  schema.getTypes.asScala
                    .find(_.getFullName == altName)
                    .toRight(AvroError.decodeMissingUnionSchema(altName, typeName))

                def altMatching =
                  alts
                    .find(_.codec.schema.exists(_.getFullName == altName))
                    .toRight(AvroError.decodeMissingUnionAlternative(altName, typeName))

                altUnionSchema.flatMap { altSchema =>
                  altMatching.flatMap { alt =>
                    alt.codec
                      .decode(container, altSchema)
                      .map(alt.prism.reverseGet)
                  }
                }

              case other =>
                val schemaTypes = schema.getTypes.asScala.toList

                alts.toList
                  .zip(schemaTypes)
                  .collectFirstSome {
                    case (alt, schema) =>
                      val decoded = alt.codec.decode(other, schema)
                      if (decoded.isRight) Some(decoded.map(alt.prism.reverseGet)) else None
                  }
                  .getOrElse {
                    Left(AvroError.decodeExhaustedAlternatives(other, typeName))
                  }
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  typeName,
                  schemaType,
                  NonEmptyList.of(Schema.Type.UNION)
                )
            }
        }
      }
    )
  }

  /**
    * @group General
    */
  implicit final val unit: Codec[Unit] =
    Codec.instance(
      Right(SchemaBuilder.builder().nullType()),
      (_, schema) => {
        schema.getType() match {
          case Schema.Type.NULL =>
            Right(null)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Unit",
                  schemaType,
                  NonEmptyList.of(Schema.Type.NULL)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.NULL =>
            if (value == null) Right(())
            else Left(AvroError.decodeUnexpectedType(value, "null", "Unit"))

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Unit",
                  schemaType,
                  NonEmptyList.of(Schema.Type.NULL)
                )
            }
        }
      }
    )

  /**
    * @group JavaUtil
    */
  implicit final val uuid: Codec[UUID] =
    Codec.instance(
      Right(LogicalTypes.uuid().addToSchema(SchemaBuilder.builder().stringType())),
      (uuid, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.uuid())
              Right(new Utf8(uuid.toString()))
            else Left(AvroError.encodeUnexpectedLogicalType(logicalType, "UUID"))

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "UUID",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.STRING =>
            val logicalType = schema.getLogicalType()
            if (logicalType == LogicalTypes.uuid()) {
              value match {
                case utf8: Utf8 =>
                  AvroError.catchNonFatal {
                    Right(UUID.fromString(utf8.toString()))
                  }
                case other =>
                  Left(AvroError.decodeUnexpectedType(other, "Utf8", "UUID"))
              }
            } else Left(AvroError.decodeUnexpectedLogicalType(logicalType, "UUID"))

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "UUID",
                  schemaType,
                  NonEmptyList.of(Schema.Type.STRING)
                )
            }
        }
      }
    )

  /**
    * @group Collection
    */
  implicit final def vector[A](implicit codec: Codec[A]): Codec[Vector[A]] =
    Codec.instance(
      codec.schema.map(Schema.createArray),
      (vector, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            vector.traverse(codec.encode(_, elementType)).map(_.asJava)

          case schemaType =>
            Left {
              AvroError
                .encodeUnexpectedSchemaType(
                  "Vector",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      },
      (value, schema) => {
        schema.getType() match {
          case Schema.Type.ARRAY =>
            val elementType = schema.getElementType()
            value match {
              case collection: java.util.Collection[_] =>
                collection.asScala.toVector.traverse(codec.decode(_, elementType))

              case other =>
                Left(AvroError.decodeUnexpectedType(other, "Collection", "Vector"))
            }

          case schemaType =>
            Left {
              AvroError
                .decodeUnexpectedSchemaType(
                  "Vector",
                  schemaType,
                  NonEmptyList.of(Schema.Type.ARRAY)
                )
            }
        }
      }
    )

  /**
    * @group Cats
    */
  implicit final val codecInvariant: Invariant[Codec] =
    new Invariant[Codec] {
      override final def imap[A, B](codec: Codec[A])(f: A => B)(g: B => A): Codec[B] =
        codec.imap(f)(g)
    }

  /**
    * @group Cats
    */
  implicit final def codecShow[A]: Show[Codec[A]] =
    Show.fromToString

  /**
    * @group Create
    */
  sealed abstract class Alt[A] {
    type B

    def codec: Codec[B]

    def prism: Prism[A, B]
  }

  private[vulcan] final object Alt {
    final def apply[A, B](
      codec: Codec[B],
      prism: Prism[A, B]
    ): Alt[A] = {
      type B0 = B
      val _codec = codec
      val _prism = prism

      new Alt[A] {
        override final type B = B0
        override final val codec: Codec[B] = _codec
        override final val prism: Prism[A, B] = _prism
      }
    }
  }

  /**
    * @group Create
    */
  sealed abstract class AltBuilder[A] {
    def apply[B](
      implicit codec: Codec[B],
      prism: Prism[A, B]
    ): Chain[Alt[A]]
  }

  private[vulcan] final object AltBuilder {
    private[this] final val Instance: AltBuilder[Any] =
      new AltBuilder[Any] {
        override final def apply[B](
          implicit codec: Codec[B],
          prism: Prism[Any, B]
        ): Chain[Alt[Any]] =
          Chain.one(Alt(codec, prism))

        override final def toString: String =
          "AltBuilder"
      }

    final def instance[A]: AltBuilder[A] =
      Instance.asInstanceOf[AltBuilder[A]]
  }

  /**
    * @group Create
    */
  sealed abstract class Field[A, B] {
    def name: String

    def access: A => B

    def codec: Codec[B]

    def doc: Option[String]

    def default: Option[B]

    def order: Option[Schema.Field.Order]

    def aliases: Seq[String]

    def props: Seq[(String, String)]
  }

  private[vulcan] final object Field {
    final def apply[A, B](
      name: String,
      access: A => B,
      codec: Codec[B],
      doc: Option[String],
      default: Option[B],
      order: Option[Schema.Field.Order],
      aliases: Seq[String],
      props: Seq[(String, String)]
    ): Field[A, B] = {
      val _name = name
      val _access = access
      val _codec = codec
      val _doc = doc
      val _default = default
      val _order = order
      val _aliases = aliases
      val _props = props

      new Field[A, B] {
        override final val name: String = _name
        override final val access: A => B = _access
        override final val codec: Codec[B] = _codec
        override final val doc: Option[String] = _doc
        override final val default: Option[B] = _default
        override final val order: Option[Schema.Field.Order] = _order
        override final val aliases: Seq[String] = _aliases
        override final val props: Seq[(String, String)] = _props
      }
    }
  }

  /**
    * @group Create
    */
  sealed abstract class FieldBuilder[A] {
    def apply[B](
      name: String,
      access: A => B,
      doc: Option[String] = None,
      default: Option[B] = None,
      order: Option[Schema.Field.Order] = None,
      aliases: Seq[String] = Seq.empty,
      props: Seq[(String, String)] = Seq.empty
    )(implicit codec: Codec[B]): FreeApplicative[Field[A, ?], B]
  }

  private[vulcan] final object FieldBuilder {
    private[this] final val Instance: FieldBuilder[Any] =
      new FieldBuilder[Any] {
        override final def apply[B](
          name: String,
          access: Any => B,
          doc: Option[String],
          default: Option[B],
          order: Option[Schema.Field.Order],
          aliases: Seq[String],
          props: Seq[(String, String)]
        )(implicit codec: Codec[B]): FreeApplicative[Field[Any, ?], B] =
          FreeApplicative.lift {
            Field(
              name = name,
              access = access,
              codec = codec,
              doc = doc,
              default = default,
              order = order,
              aliases = aliases,
              props = props
            )
          }

        override final def toString: String =
          "FieldBuilder"
      }

    final def instance[A]: FieldBuilder[A] =
      Instance.asInstanceOf[FieldBuilder[A]]
  }
}
