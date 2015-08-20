package io.vertx.ext.stomp;

import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.stomp.impl.Transaction;
import io.vertx.ext.stomp.impl.Transactions;
import io.vertx.ext.stomp.utils.Headers;

import java.util.List;
import java.util.UUID;

/**
 * STOMP compliant actions executed when receiving a {@code SEND} sf.frame().
 * <p/>
 * If the {@code SEND} frame specifies a transaction, the message delivery is postponed until the transaction commit.
 * <p/>
 * The handler computes the {@code MESSAGE} frame from the {@code SEND} sf.frame(). It computes a {@code message-id} and
 * {@code ack} id if needed. If requested the {@code RECEIPT} frame is sent once the {@code MESSAGE} frame has been
 * sent to all matching subscriptions.
 * <p/>
 * If the {@code SEND} frame requires an acknowledgment, the {@code message-id} is added to the list of messages
 * waiting for acknowledgment.
 * <p/>
 * This handler is thread safe.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class DefaultSendHandler implements Handler<ServerFrame> {

  @Override
  public void handle(ServerFrame sf) {
    String destination = sf.frame().getHeader(Frame.DESTINATION);
    if (destination == null) {
      sf.connection().write(Frames.createErrorFrame(
          "Destination header missing",
          Headers.create(sf.frame().getHeaders()), "Invalid send frame - the " +
              "'destination' must be set"));
      sf.connection().close();
      return;
    }

    // Handle transaction
    String txId = sf.frame().getHeader(Frame.TRANSACTION);
    if (txId != null) {
      Transaction transaction = Transactions.instance().getTransaction(sf.connection(), txId);
      if (transaction == null) {
        // No transaction.
        Frame errorFrame = Frames.createErrorFrame(
            "No transaction",
            Headers.create(Frame.DESTINATION, destination, Frame.TRANSACTION, txId),
            "Message delivery failed - unknown transaction id");
        sf.connection().write(errorFrame);
        sf.connection().close();
        return;
      } else {
        if (!transaction.addFrameToTransaction(sf.frame())) {
          // Frame not added to transaction
          Frame errorFrame = Frames.createErrorFrame("Frame not added to transaction",
              Headers.create(Frame.DESTINATION, destination, Frame.TRANSACTION, txId),
              "Message delivery failed - the frame cannot be added to the transaction - the number of allowed thread " +
                  "may have been reached");
          Transactions.instance().unregisterTransactionsFromConnection(sf.connection());
          sf.connection().write(errorFrame);
          sf.connection().close();
          return;
        }
        Frames.handleReceipt(sf.frame(), sf.connection());
        // No delivery in transactions.
        return;
      }
    }

    final Destination dest = sf.connection().handler().getDestination(destination);
    if (dest == null && sf.connection().server().options().isSendErrorOnNoSubscriptions()) {
      Frame errorFrame = Frames.createErrorFrame(
          "No subscriptions",
          Headers.create(Frame.DESTINATION, destination),
          "Message delivery failed - no subscriptions on this destination");
      sf.connection().write(errorFrame);
      sf.connection().close();
      return;
    }

    if (dest != null) {
      dest.dispatch(sf.connection(), sf.frame());
    }

    Frames.handleReceipt(sf.frame(), sf.connection());
  }

}
