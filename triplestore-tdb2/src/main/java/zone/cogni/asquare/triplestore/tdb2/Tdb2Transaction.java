package zone.cogni.asquare.triplestore.tdb2;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zone.cogni.asquare.transactional.ASquareTransaction;
import zone.cogni.asquare.transactional.ASquareTransactionType;
import zone.cogni.asquare.transactional.CheckedSupplier;

import static zone.cogni.asquare.transactional.ASquareTransactionType.READ;
import static zone.cogni.asquare.transactional.ASquareTransactionType.WRITE;


public class Tdb2Transaction implements ASquareTransaction {

  private static final Logger log = LoggerFactory.getLogger(Tdb2Transaction.class);
  private final Dataset dataset;
  private final ThreadLocal<ASquareTransactionType> currentTransactionInThread = new ThreadLocal<>();

  public Tdb2Transaction(Dataset dataset) {
    this.dataset = dataset;
  }

  @Override
  public <T> T readChecked(CheckedSupplier<T> read) throws Throwable {
    ASquareTransactionType parentType = currentTransactionInThread.get();
    if (parentType == null) {
      currentTransactionInThread.set(READ);
    }
    else {
      if (dataset.isInTransaction()) {
        return read.get();
      }
      else {
        resetCurrentTransactionInThread();
        throw new IllegalStateException("Obsolete transaction state: " + parentType.name() +
                                        " from thread: " + Thread.currentThread().getName());
      }
    }

    T t;
    try {
      dataset.begin(ReadWrite.READ);
      t = read.get();
    }
    catch (Throwable e) {
      log.info("Failure in READ transaction, ending ...");
      dataset.end();
      throw e;
    }
    finally {
      resetCurrentTransactionInThread();
    }

    dataset.end();
    return t;
  }

  @Override
  public <T> T writeChecked(CheckedSupplier<T> write) throws Throwable {
    ASquareTransactionType parentType = currentTransactionInThread.get();
    if (parentType == null) {
      currentTransactionInThread.set(WRITE);
    }
    else {
      if (dataset.isInTransaction()) {
        if (parentType == READ) {
          throw new IllegalStateException("WRITE transaction was nested inside READ transaction");
        }
        else {
          return write.get();
        }
      }
      else {
        resetCurrentTransactionInThread();
        throw new IllegalStateException("Obsolete transaction state: " + parentType.name() +
                                        " from thread: " + Thread.currentThread().getName());
      }
    }

    T t;
    try {
      dataset.begin(ReadWrite.WRITE);
      t = write.get();
    }
    catch (Throwable e) {
      log.info("Failure in WRITE transaction, aborting ...");
      dataset.abort();
      throw e;
    }
    finally {
      resetCurrentTransactionInThread();
    }

    dataset.commit();
    return t;
  }

  private void resetCurrentTransactionInThread() {
    currentTransactionInThread.set(null);
  }
}
