/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package zio

import scala.annotation.tailrec

sealed trait Cause[+E] extends Product with Serializable { self =>
  import Cause._

  /**
   * Returns a cause that fails for this cause and the specified cause, in parallel.
   */
  final def &&[E1 >: E](that: Cause[E1]): Cause[E1] = Both(self, that)

  /**
   * Returns a cause that fails for this cause and the specified cause, in sequence.
   */
  final def ++[E1 >: E](that: Cause[E1]): Cause[E1] =
    if (self eq Empty) that else if (that eq Empty) self else Then(self, that)

  /**
   * Determines if this cause contains or is equal to the specified cause.
   */
  final def contains[E1 >: E](that: Cause[E1]): Boolean =
    (self eq that) || foldLeft[Boolean](false) {
      case (acc, cause) => acc || (cause == that)
    }

  /**
   * Extracts a list of non-recoverable errors from the `Cause`.
   */
  final def defects: List[Throwable] =
    self
      .foldLeft(List.empty[Throwable]) {
        case (z, Die(v)) => v :: z
      }
      .reverse

  final def died: Boolean =
    dieOption.isDefined

  /**
   * Returns the `Throwable` associated with the first `Die` in this `Cause` if
   * one exists.
   */
  final def dieOption: Option[Throwable] =
    find { case Die(t) => t }

  final def failed: Boolean =
    failureOption.isDefined

  /**
   * Returns the `E` associated with the first `Fail` in this `Cause` if one
   * exists.
   */
  def failureOption: Option[E] =
    find { case Fail(e) => e }

  /**
   * Retrieve the first checked error on the `Left` if available,
   * if there are no checked errors return the rest of the `Cause`
   * that is known to contain only `Die` or `Interrupt` causes.
   * */
  final def failureOrCause: Either[E, Cause[Nothing]] = failureOption match {
    case Some(error) => Left(error)
    case None        => Right(self.asInstanceOf[Cause[Nothing]]) // no E inside this cause, can safely cast
  }

  /**
   * Produces a list of all recoverable errors `E` in the `Cause`.
   */
  final def failures: List[E] =
    self
      .foldLeft(List.empty[E]) {
        case (z, Fail(v)) => v :: z
      }
      .reverse

  final def flatMap[E1](f: E => Cause[E1]): Cause[E1] = self match {
    case Empty                => Empty
    case Fail(value)          => f(value)
    case c @ Die(_)           => c
    case Interrupt(id)        => Interrupt(id)
    case Then(left, right)    => Then(left.flatMap(f), right.flatMap(f))
    case Both(left, right)    => Both(left.flatMap(f), right.flatMap(f))
    case Traced(cause, trace) => Traced(cause.flatMap(f), trace)
    case Meta(cause, data)    => Meta(cause.flatMap(f), data)
  }

  final def flatten[E1](implicit ev: E <:< Cause[E1]): Cause[E1] =
    self flatMap (e => e)

  /**
   * Determines if the `Cause` contains an interruption.
   */
  final def interrupted: Boolean =
    find { case Interrupt(_) => () }.isDefined

  /**
   * Returns a set of interruptors, fibers that interrupted the fiber described
   * by this `Cause`.
   */
  final def interruptors: Set[Fiber.Id] =
    foldLeft[Set[Fiber.Id]](Set()) {
      case (acc, Interrupt(fiberId)) => acc + fiberId
    }

  /**
   * Determines if the `Cause` is empty.
   */
  final def isEmpty: Boolean =
    (self eq Empty) || foldLeft(true) {
      case (acc, _: Empty.type) => acc
      case (_, Die(_))          => false
      case (_, Fail(_))         => false
      case (_, Interrupt(_))    => false
    }

  final def fold[Z](
    empty: => Z,
    failCase: E => Z,
    dieCase: Throwable => Z,
    interruptCase: Fiber.Id => Z
  )(thenCase: (Z, Z) => Z, bothCase: (Z, Z) => Z, tracedCase: (Z, ZTrace) => Z): Z =
    self match {
      case Empty => empty
      case Fail(value) =>
        failCase(value)
      case Die(value) =>
        dieCase(value)
      case Interrupt(fiberId) =>
        interruptCase(fiberId)
      case Then(left, right) =>
        thenCase(
          left.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase),
          right.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase)
        )
      case Both(left, right) =>
        bothCase(
          left.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase),
          right.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase)
        )
      case Traced(cause, trace) =>
        tracedCase(
          cause.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase),
          trace
        )
      case Meta(cause, _) =>
        cause.fold(empty, failCase, dieCase, interruptCase)(thenCase, bothCase, tracedCase)
    }

  final def map[E1](f: E => E1): Cause[E1] =
    flatMap(f andThen fail)

  /**
   * Returns a `Cause` that has been stripped of all tracing information.
   */
  final def untraced: Cause[E] =
    self match {
      case Traced(cause, _)  => cause.untraced
      case Meta(cause, data) => Meta(cause.untraced, data)

      case Empty            => Empty
      case c @ Fail(_)      => c
      case c @ Die(_)       => c
      case c @ Interrupt(_) => c

      case Then(left, right) => Then(left.untraced, right.untraced)
      case Both(left, right) => Both(left.untraced, right.untraced)
    }

  /**
   * Returns a `String` with the cause pretty-printed.
   */
  final def prettyPrint: String = {
    sealed trait Segment
    sealed trait Step extends Segment

    final case class Sequential(all: List[Step])     extends Segment
    final case class Parallel(all: List[Sequential]) extends Step
    final case class Failure(lines: List[String])    extends Step

    def prefixBlock[A](values: List[String], p1: String, p2: String): List[String] =
      values match {
        case Nil => Nil
        case head :: tail =>
          (p1 + head) :: tail.map(p2 + _)
      }

    def parallelSegments(cause: Cause[Any], maybeData: Option[Data]): List[Sequential] =
      cause match {
        case Cause.Both(left, right) => parallelSegments(left, maybeData) ++ parallelSegments(right, maybeData)
        case _                       => List(causeToSequential(cause, maybeData))
      }

    def linearSegments(cause: Cause[Any], maybeData: Option[Data]): List[Step] =
      cause match {
        case Cause.Then(first, second) => linearSegments(first, maybeData) ++ linearSegments(second, maybeData)
        case _                         => causeToSequential(cause, maybeData).all
      }

    // Inline definition of `StringOps.lines` to avoid calling either of `.linesIterator` or `.lines`
    // since both are deprecated in either 2.11 or 2.13 respectively.
    def lines(str: String): List[String] = augmentString(str).linesWithSeparators.map(_.stripLineEnd).toList

    def renderThrowable(e: Throwable, maybeData: Option[Data]): List[String] = {
      val stackless = maybeData.fold(false)(_.stackless)
      if (stackless) List(e.toString)
      else {
        import java.io.{ PrintWriter, StringWriter }
        val sw = new StringWriter()
        val pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        lines(sw.toString)
      }
    }

    def renderTrace(maybeTrace: Option[ZTrace]): List[String] =
      maybeTrace.fold("No ZIO Trace available." :: Nil) { trace =>
        "" :: lines(trace.prettyPrint)
      }

    def renderFail(error: List[String], maybeTrace: Option[ZTrace]): Sequential =
      Sequential(
        List(Failure("A checked error was not handled." :: error ++ renderTrace(maybeTrace)))
      )

    def renderFailThrowable(t: Throwable, maybeTrace: Option[ZTrace], maybeData: Option[Data]): Sequential =
      renderFail(renderThrowable(t, maybeData), maybeTrace)

    def renderDie(t: Throwable, maybeTrace: Option[ZTrace], maybeData: Option[Data]): Sequential =
      Sequential(
        List(Failure("An unchecked error was produced." :: renderThrowable(t, maybeData) ++ renderTrace(maybeTrace)))
      )

    def renderInterrupt(fiberId: Fiber.Id, maybeTrace: Option[ZTrace]): Sequential =
      Sequential(
        List(Failure(s"An interrupt was produced by #${fiberId.seqNumber}." :: renderTrace(maybeTrace)))
      )

    def causeToSequential(cause: Cause[Any], maybeData: Option[Data]): Sequential =
      cause match {
        case Empty => Sequential(Nil)

        case Cause.Fail(t: Throwable) =>
          renderFailThrowable(t, None, maybeData)
        case Cause.Fail(error) =>
          renderFail(lines(error.toString), None)
        case Cause.Die(t) =>
          renderDie(t, None, maybeData)
        case Cause.Interrupt(fid) =>
          renderInterrupt(fid, None)

        case t: Cause.Then[Any] => Sequential(linearSegments(t, maybeData))
        case b: Cause.Both[Any] => Sequential(List(Parallel(parallelSegments(b, maybeData))))
        case Traced(c, trace) =>
          c match {
            case Cause.Fail(t: Throwable) =>
              renderFailThrowable(t, Some(trace), maybeData)
            case Cause.Fail(error) =>
              renderFail(lines(error.toString), Some(trace))
            case Cause.Die(t) =>
              renderDie(t, Some(trace), maybeData)
            case Cause.Interrupt(fid) =>
              renderInterrupt(fid, Some(trace))
            case _ =>
              Sequential(
                Failure("An error was rethrown with a new trace." :: renderTrace(Some(trace))) ::
                  causeToSequential(c, maybeData).all
              )
          }
        case Meta(cause, data) =>
          causeToSequential(cause, Some(data))

      }

    def format(segment: Segment): List[String] =
      segment match {
        case Failure(lines) =>
          prefixBlock(lines, "─", " ")
        case Parallel(all) =>
          List(("══╦" * (all.size - 1)) + "══╗") ++
            all.foldRight[List[String]](Nil) {
              case (current, acc) =>
                prefixBlock(acc, "  ║", "  ║") ++
                  prefixBlock(format(current), "  ", "  ")
            }
        case Sequential(all) =>
          all.flatMap { segment =>
            List("║") ++
              prefixBlock(format(segment), "╠", "║")
          } ++ List("▼")
      }

    val sequence = causeToSequential(this, None)

    ("Fiber failed." :: {
      sequence match {
        // use simple report for single failures
        case Sequential(List(Failure(cause))) => cause

        case _ => format(sequence).updated(0, "╥")
      }
    }).mkString("\n")
  }

  /**
   * Squashes a `Cause` down to a single `Throwable`, chosen to be the
   * "most important" `Throwable`.
   */
  final def squash(implicit ev: E <:< Throwable): Throwable =
    squashWith(ev)

  /**
   * Squashes a `Cause` down to a single `Throwable`, chosen to be the
   * "most important" `Throwable`.
   */
  final def squashWith(f: E => Throwable): Throwable =
    failureOption.map(f) orElse
      (if (interrupted) Some(new InterruptedException) else None) orElse
      defects.headOption getOrElse (new InterruptedException)

  /**
   * Remove all `Fail` and `Interrupt` nodes from this `Cause`,
   * return only `Die` cause/finalizer defects.
   */
  final def stripFailures: Option[Cause[Nothing]] =
    self match {
      case Empty        => None
      case Interrupt(_) => None
      case Fail(_)      => None
      case d @ Die(_)   => Some(d)

      case Both(l, r) =>
        (l.stripFailures, r.stripFailures) match {
          case (Some(l), Some(r)) => Some(Both(l, r))
          case (Some(l), None)    => Some(l)
          case (None, Some(r))    => Some(r)
          case (None, None)       => None
        }

      case Then(l, r) =>
        (l.stripFailures, r.stripFailures) match {
          case (Some(l), Some(r)) => Some(Then(l, r))
          case (Some(l), None)    => Some(l)
          case (None, Some(r))    => Some(r)
          case (None, None)       => None
        }

      case Traced(c, trace) => c.stripFailures.map(Traced(_, trace))
      case Meta(c, data)    => c.stripFailures.map(Meta(_, data))
    }

  /**
   * Grabs a list of execution traces from the cause.
   */
  final def traces: List[ZTrace] =
    self
      .foldLeft(List.empty[ZTrace]) {
        case (z, Traced(_, trace)) => trace :: z
      }
      .reverse

  private def find[Z](f: PartialFunction[Cause[E], Z]): Option[Z] = {
    @tailrec
    def loop(cause: Cause[E], stack: List[Cause[E]]): Option[Z] =
      f.lift(cause) match {
        case Some(z) => Some(z)
        case None =>
          cause match {
            case Then(left, right) => loop(left, right :: stack)
            case Both(left, right) => loop(left, right :: stack)
            case Traced(cause, _)  => loop(cause, stack)
            case Meta(cause, _)    => loop(cause, stack)
            case _ =>
              stack match {
                case hd :: tl => loop(hd, tl)
                case Nil      => None
              }
          }
      }
    loop(self, Nil)
  }

  private def foldLeft[Z](z: Z)(f: PartialFunction[(Z, Cause[E]), Z]): Z = {
    @tailrec
    def loop(z: Z, cause: Cause[E], stack: List[Cause[E]]): Z =
      (f.applyOrElse[(Z, Cause[E]), Z](z -> cause, _ => z), cause) match {
        case (z, Then(left, right)) => loop(z, left, right :: stack)
        case (z, Both(left, right)) => loop(z, left, right :: stack)
        case (z, Traced(cause, _))  => loop(z, cause, stack)
        case (z, Meta(cause, _))    => loop(z, cause, stack)
        case (z, _) =>
          stack match {
            case hd :: tl => loop(z, hd, tl)
            case Nil      => z
          }
      }
    loop(z, self, Nil)
  }
}

object Cause extends Serializable {
  final val empty: Cause[Nothing]                               = Empty
  final def die(defect: Throwable): Cause[Nothing]              = Die(defect)
  final def fail[E](error: E): Cause[E]                         = Fail(error)
  final def interrupt(fiberId: Fiber.Id): Cause[Nothing]        = Interrupt(fiberId)
  final def stack[E](cause: Cause[E]): Cause[E]                 = Meta(cause, Data(false))
  final def stackless[E](cause: Cause[E]): Cause[E]             = Meta(cause, Data(true))
  final def traced[E](cause: Cause[E], trace: ZTrace): Cause[E] = Traced(cause, trace)

  final case object Empty extends Cause[Nothing] {
    override final def equals(that: Any): Boolean = that match {
      case _: Empty.type     => true
      case Then(left, right) => this == left && this == right
      case Both(left, right) => this == left && this == right
      case traced: Traced[_] => this == traced.cause
      case meta: Meta[_]     => this == meta.cause
      case _                 => false
    }
  }

  /**
   * Converts the specified `Cause[Option[E]]` to an `Option[Cause[E]]` by
   * recursively stripping out any failures with the error `None`.
   */
  final def sequenceCauseOption[E](c: Cause[Option[E]]): Option[Cause[E]] =
    c match {
      case Empty                      => Some(Empty)
      case Cause.Traced(cause, trace) => sequenceCauseOption(cause).map(Cause.Traced(_, trace))
      case Cause.Meta(cause, data)    => sequenceCauseOption(cause).map(Cause.Meta(_, data))
      case Cause.Interrupt(id)        => Some(Cause.Interrupt(id))
      case d @ Cause.Die(_)           => Some(d)
      case Cause.Fail(Some(e))        => Some(Cause.Fail(e))
      case Cause.Fail(None)           => None
      case Cause.Then(left, right) =>
        (sequenceCauseOption(left), sequenceCauseOption(right)) match {
          case (Some(cl), Some(cr)) => Some(Cause.Then(cl, cr))
          case (None, Some(cr))     => Some(cr)
          case (Some(cl), None)     => Some(cl)
          case (None, None)         => None
        }

      case Cause.Both(left, right) =>
        (sequenceCauseOption(left), sequenceCauseOption(right)) match {
          case (Some(cl), Some(cr)) => Some(Cause.Both(cl, cr))
          case (None, Some(cr))     => Some(cr)
          case (Some(cl), None)     => Some(cl)
          case (None, None)         => None
        }
    }

  private final case class Fail[E](value: E) extends Cause[E] {
    override final def equals(that: Any): Boolean = that match {
      case fail: Fail[_]     => value == fail.value
      case c @ Then(_, _)    => sym(empty)(this, c)
      case c @ Both(_, _)    => sym(empty)(this, c)
      case traced: Traced[_] => this == traced.cause
      case meta: Meta[_]     => this == meta.cause
      case _                 => false
    }
  }

  object Fail {
    def apply[E](value: E): Cause[E] =
      new Fail(value)
  }

  private final case class Die(value: Throwable) extends Cause[Nothing] {
    override final def equals(that: Any): Boolean = that match {
      case die: Die          => value == die.value
      case c @ Then(_, _)    => sym(empty)(this, c)
      case c @ Both(_, _)    => sym(empty)(this, c)
      case traced: Traced[_] => this == traced.cause
      case meta: Meta[_]     => this == meta.cause
      case _                 => false
    }
  }

  object Die {
    final def apply(value: Throwable): Cause[Nothing] =
      new Die(value)
  }

  final case class Interrupt(fiberId: Fiber.Id) extends Cause[Nothing] {
    override final def equals(that: Any): Boolean =
      (this eq that.asInstanceOf[AnyRef]) || (that match {
        case interrupt: Interrupt => fiberId == interrupt.fiberId
        case c @ Then(_, _)       => sym(empty)(this, c)
        case c @ Both(_, _)       => sym(empty)(this, c)
        case traced: Traced[_]    => this == traced.cause
        case meta: Meta[_]        => this == meta.cause
        case _                    => false
      })
  }

  // Traced is excluded completely from equals & hashCode
  private final case class Traced[E](cause: Cause[E], trace: ZTrace) extends Cause[E] {
    override final def hashCode: Int = cause.hashCode()
    override final def equals(obj: Any): Boolean = obj match {
      case traced: Traced[_] => cause == traced.cause
      case meta: Meta[_]     => cause == meta.cause
      case _                 => cause == obj
    }
  }

  object Traced {
    def apply[E](cause: Cause[E], trace: ZTrace): Cause[E] =
      new Traced(cause, trace)
  }

  // Meta is excluded completely from equals & hashCode
  private final case class Meta[E](cause: Cause[E], data: Data) extends Cause[E] {
    override final def hashCode: Int = cause.hashCode
    override final def equals(obj: Any): Boolean = obj match {
      case traced: Traced[_] => cause == traced.cause
      case meta: Meta[_]     => cause == meta.cause
      case _                 => cause == obj
    }
  }

  private final case class Then[E](left: Cause[E], right: Cause[E]) extends Cause[E] { self =>
    override final def equals(that: Any): Boolean = that match {
      case traced: Traced[_] => self.equals(traced.cause)
      case meta: Meta[_]     => self.equals(meta.cause)
      case other: Cause[_]   => eq(other) || sym(assoc)(other, self) || sym(dist)(self, other) || sym(empty)(self, other)
      case _                 => false
    }
    override final def hashCode: Int = Cause.hashCode(self)

    private def eq(that: Cause[Any]): Boolean = (self, that) match {
      case (tl: Then[_], tr: Then[_]) => tl.left == tr.left && tl.right == tr.right
      case _                          => false
    }

    private def assoc(l: Cause[Any], r: Cause[Any]): Boolean = (l, r) match {
      case (Then(Then(al, bl), cl), Then(ar, Then(br, cr))) => al == ar && bl == br && cl == cr
      case _                                                => false
    }

    private def dist(l: Cause[Any], r: Cause[Any]): Boolean = (l, r) match {
      case (Then(al, Both(bl, cl)), Both(Then(ar1, br), Then(ar2, cr)))
          if ar1 == ar2 && al == ar1 && bl == br && cl == cr =>
        true
      case (Then(Both(al, bl), cl), Both(Then(ar, cr1), Then(br, cr2)))
          if cr1 == cr2 && al == ar && bl == br && cl == cr1 =>
        true
      case _ => false
    }
  }

  object Then {
    def apply[E](left: Cause[E], right: Cause[E]): Cause[E] =
      new Then(left, right)
  }

  private final case class Both[E](left: Cause[E], right: Cause[E]) extends Cause[E] { self =>
    override final def equals(that: Any): Boolean = that match {
      case traced: Traced[_] => self.equals(traced.cause)
      case meta: Meta[_]     => self.equals(meta.cause)
      case other: Cause[_] =>
        eq(other) || sym(assoc)(self, other) || sym(dist)(self, other) || comm(other) || sym(empty)(self, other)
      case _ => false
    }
    override final def hashCode: Int = Cause.hashCode(self)

    private def eq(that: Cause[Any]) = (self, that) match {
      case (bl: Both[_], br: Both[_]) => bl.left == br.left && bl.right == br.right
      case _                          => false
    }

    private def assoc(l: Cause[Any], r: Cause[Any]): Boolean = (l, r) match {
      case (Both(Both(al, bl), cl), Both(ar, Both(br, cr))) => al == ar && bl == br && cl == cr
      case _                                                => false
    }

    private def dist(l: Cause[Any], r: Cause[Any]): Boolean = (l, r) match {
      case (Both(Then(al1, bl), Then(al2, cl)), Then(ar, Both(br, cr)))
          if al1 == al2 && al1 == ar && bl == br && cl == cr =>
        true
      case (Both(Then(al, cl1), Then(bl, cl2)), Then(Both(ar, br), cr))
          if cl1 == cl2 && al == ar && bl == br && cl1 == cr =>
        true
      case _ => false
    }

    private def comm(that: Cause[Any]): Boolean = (self, that) match {
      case (Both(al, bl), Both(ar, br)) => al == br && bl == ar
      case _                            => false
    }
  }

  object Both {
    def apply[E](left: Cause[E], right: Cause[E]): Cause[E] =
      new Both(left, right)
  }

  private final case class Data(stackless: Boolean)

  private[Cause] def empty(l: Cause[Any], r: Cause[Any]): Boolean = (l, r) match {
    case (Then(a, Cause.Empty), b) => a == b
    case (Then(Cause.Empty, a), b) => a == b
    case (Both(a, Cause.Empty), b) => a == b
    case (Both(Cause.Empty, a), b) => a == b
    case _                         => false
  }

  private[Cause] def sym(f: (Cause[Any], Cause[Any]) => Boolean): (Cause[Any], Cause[Any]) => Boolean =
    (l, r) => f(l, r) || f(r, l)

  private[Cause] def hashCode(c: Cause[_]): Int = flatten(c) match {
    case Nil                         => Empty.hashCode
    case set :: Nil if set.size == 1 => set.head.hashCode
    case seq                         => seq.hashCode
  }

  /**
   * Flattens a cause to a sequence of sets of causes, where each set
   * represents causes that fail in parallel and sequential sets represent
   * causes that fail after each other.
   */
  private[Cause] def flatten(c: Cause[_]): List[Set[Cause[_]]] = {

    @tailrec
    def loop(causes: List[Cause[_]], flattened: List[Set[Cause[_]]]): List[Set[Cause[_]]] = {
      val (parallel, sequential) = causes.foldLeft((Set.empty[Cause[_]], List.empty[Cause[_]])) {
        case ((parallel, sequential), cause) =>
          val (set, seq) = step(cause)
          (parallel ++ set, sequential ++ seq)
      }
      val updated = if (parallel.nonEmpty) parallel :: flattened else flattened
      if (sequential.isEmpty) updated.reverse
      else loop(sequential, updated)
    }

    loop(List(c), List.empty)
  }

  /**
   * Takes one step in evaluating a cause, returning a set of causes that fail
   * in parallel and a list of causes that fail sequentially after those causes.
   */
  private[Cause] def step(c: Cause[_]): (Set[Cause[_]], List[Cause[_]]) = {

    @tailrec
    def loop(
      cause: Cause[_],
      stack: List[Cause[_]],
      parallel: Set[Cause[_]],
      sequential: List[Cause[_]]
    ): (Set[Cause[_]], List[Cause[_]]) = cause match {
      case Empty => if (stack.isEmpty) (parallel, sequential) else loop(stack.head, stack.tail, parallel, sequential)
      case Then(left, right) =>
        left match {
          case Empty        => loop(right, stack, parallel, sequential)
          case Then(l, r)   => loop(Cause.Then(l, Cause.Then(r, right)), stack, parallel, sequential)
          case Both(l, r)   => loop(Cause.Both(Cause.Then(l, right), Cause.Then(r, right)), stack, parallel, sequential)
          case Traced(c, _) => loop(Cause.Then(c, right), stack, parallel, sequential)
          case Meta(c, _)   => loop(Cause.Then(c, right), stack, parallel, sequential)
          case o            => loop(o, stack, parallel, right :: sequential)
        }
      case Both(left, right) => loop(left, right :: stack, parallel, sequential)
      case Traced(cause, _)  => loop(cause, stack, parallel, sequential)
      case Meta(cause, _)    => loop(cause, stack, parallel, sequential)
      case o =>
        if (stack.isEmpty) (parallel ++ Set(o), sequential)
        else loop(stack.head, stack.tail, parallel ++ Set(o), sequential)
    }

    loop(c, List.empty, Set.empty, List.empty)
  }
}
