package com.rockthejvm.part2effects

import cats.effect.{IO, IOApp}

import scala.concurrent.Future
import scala.util.Random

object IOTraversal extends IOApp.Simple {

  import scala.concurrent.ExecutionContext.Implicits.global

  def heavyComputation(string: String): Future[Int] = Future {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }

  val workload: List[String] = List("I quite like CE", "Scala is great", "looking forward to some awesome stuff")

  def clunkyFutures(): Unit = {
    val futures: List[Future[Int]] = workload.map(heavyComputation)
    // Future[List[Int]] would be hard to obtain
    futures.foreach(_.foreach(println))
  }

  // traverse

  import cats.Traverse
  import cats.instances.list.*

  val listTraverse = Traverse[List]

  def traverseFutures(): Unit = {
    val singleFuture: Future[List[Int]] = listTraverse.traverse(workload)(heavyComputation)
    // ^^ this stores ALL the results
    singleFuture.foreach(println)
  }

  import com.rockthejvm.utils.*

  // traverse for IO
  def computeAsIO(string: String): IO[Int] = IO {
    Thread.sleep(Random.nextInt(1000))
    string.split(" ").length
  }.debug

  val ios: List[IO[Int]] = workload.map(computeAsIO)
  val singleIO: IO[List[Int]] = listTraverse.traverse(workload)(computeAsIO) // computeAsIO(element) = IO[Int]

  // parallel traversal

  import cats.syntax.parallel.* // parTraverse extension method

  val parallelSingleIO: IO[List[Int]] = workload.parTraverse(computeAsIO)

  /**
   * Exercises
   */
  // hint: use Traverse API
  def sequence[A](listOfIOs: List[IO[A]]): IO[List[A]] = {
    listTraverse.traverse(listOfIOs)(identity) // lambda(elem) = IO[A]
  }

  // hard version
  def sequence_v2[F[_] : Traverse, A](wrapperOfIOs: F[IO[A]]): IO[F[A]] = {
    Traverse[F].traverse(wrapperOfIOs)(identity)
  }

  // parallel version
  def parSequence[A](listOfIOs: List[IO[A]]): IO[List[A]] = {
    listOfIOs.parTraverse(identity)
  }

  // hard version
  def parSequence_v2[F[_] : Traverse, A](wrapperOfIOs: F[IO[A]]): IO[F[A]] = {
    wrapperOfIOs.parTraverse(identity)
  }

  // existing sequence API
  val singleIO_v2: IO[List[Int]] = listTraverse.sequence(ios)

  // parallel sequencing
  val parallelSingleIO_v2: IO[List[Int]] = parSequence(ios) // from the exercise
  val parallelSingleIO_v3: IO[List[Int]] = ios.parSequence // extension method from the Parallel syntax package

  override def run: IO[Unit] =
    parallelSingleIO.map(_.sum).debug.void
}

