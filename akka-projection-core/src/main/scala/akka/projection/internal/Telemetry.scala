/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.internal

import scala.collection.immutable

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.annotation.InternalStableApi
import akka.projection.ProjectionId

/**
 * Service Provider Interface (SPI) for collecting metrics from projections.
 *
 * Implementations must include a single constructor with two arguments: [[ProjectionId]]
 * and [[ActorSystem]].  To setup your implementation, add a setting on your `application.conf`:
 *
 * {{{
 * akka.projection.telemetry.implementation-class = com.example.MyMetrics
 * }}}
 */
@InternalStableApi
trait Telemetry {

  /** Invoked when a projection is stopped. The reason for stopping is unspecified, can be a
   * graceful stop or a failure (see [[Telemetry.failed]]).
   */
  def stopped(): Unit

  /**
   * Invoked when a projection processing an envelope fails (even after all retry attempts).  The
   * projection may then be restarted by a supervisor.
   *
   * @param cause exception thrown by the errored envelope handler.
   */
  def failed(cause: Throwable): Unit

  /**
   * Invoked as soon as the envelope is read, deserialised and ready to be processed.
   *
   * @param envelope the envelope that's ready for processing.  The type `Envelope` will always
   *                 represent a single item as stored in the event log.
   * @return an externally-provided context that will propagate with the envelope until [[Telemetry.afterProcess]]
   */
  def beforeProcess[Envelope](envelope: Envelope): AnyRef

  /**
   * Invoked after processing an event such that it is visible by the read-side threads (data is
   * committed).  This method is granted to be invoked after the envelope handler has committed but
   * may or may not happen after the offset was committed (depending on the projection semantics).
   *
   * @param externalContext the context produced by [[Telemetry.beforeProcess]] and attached to the processed envelope.
   */
  def afterProcess(externalContext: AnyRef): Unit

  /**
   * Invoked when the offset is committed.
   *
   * @param numberOfEnvelopes number of envelopes marked as committed when committing this offset.  This takes
   *                  into consideration both batched processing (only commit one offset every N
   *                  envelopes) and grouped handling (user code processes multiple envelopes at
   *                  once).
   */
  def onOffsetStored(numberOfEnvelopes: Int): Unit

  /**
   * Invoked when processing an envelope errors.  When using a [[akka.projection.HandlerRecoveryStrategy]] that
   * retries, this method will be invoked as many times as retries.  If the error propagates and
   * causes the projection to fail [[Telemetry.failed]] will be invoked.
   *
   * @param cause exception thrown by the errored envelope handler.
   */
  def error(cause: Throwable): Unit
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TelemetryProvider {
  def start(projectionId: ProjectionId, system: ActorSystem[_]): Telemetry = {
    val dynamicAccess = system.dynamicAccess
    if (system.settings.config.hasPath("akka.projection.telemetry.implementation-class")) {
      val telemetryFqcn: String = system.settings.config.getString("akka.projection.telemetry.implementation-class")
      dynamicAccess
        .createInstanceFor[Telemetry](
          telemetryFqcn,
          immutable.Seq((classOf[ProjectionId], projectionId), (classOf[ActorSystem[_]], system)))
        .get
    } else {
      NoopTelemetry
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object NoopTelemetry extends Telemetry {
  override def failed(cause: Throwable): Unit = {}

  override def stopped(): Unit = {}

  override def beforeProcess[Envelope](envelope: Envelope): AnyRef = NotUsed

  override def afterProcess(externalContext: AnyRef): Unit = {}

  override def onOffsetStored(numberOfEnvelopes: Int): Unit = {}

  override def error(cause: Throwable): Unit = {}

}