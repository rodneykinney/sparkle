package nest.sparkle.util

/** @define recoverable An iterator that will recreate a base iterator on exceptions.
  * A RecoverableIterator can be used to wrap an underlying iterator
  * that may produce timeout exceptions. The Recoverable iterator allows the
  * caller to encapsulate the retry logic, and explose a single iterable to
  * it the callers users needs to create multiple iterators on in the inside.
  *
  * @define threadSafety The Iterator api expects that hasNext() and next() will be called sequentially
  * (not concurrently). Accordingly, the createIteratorFn and recoverError
  * functions will not be called concurrently from multiple threads if the
  * Iterator api is properly used.
  *
  * @define createIteratorFn function that produces an iterator. This function is
  * called as the RecoverableIterator is instantiated, and again after errors
  * are caught.
  *
  * @define recoverErrors partial function that catches expected errors during
  * the iteration (e.g. timeouts).
  *
  * $recoverable
  */
object RecoverableIterator {
  /** $recoverable
    * @param createIteratorFn $createIteratorFn
    * @param errors $recoverErrors
    * $threadSafety
    */
  def apply[T](createIteratorFn: => Iterator[T])(errors: PartialFunction[Throwable, Unit]): RecoverableIterator[T] = {
    new RecoverableIterator(createIteratorFn)(errors)
  }
}

/** @define recoverable An iterator that will recreate a base iterator on exceptions.
  * A RecoverableIterator exposes an Iterator interface, but is constructed with 
  * a generator function that produces the underlying iterator. The generator function
  * is rerun after known exceptions. 
  *
  * @define threadSafety The Iterator api expects that hasNext() and next() will be called sequentially
  * (not concurrently). Accordingly, the createIteratorFn and recoverError
  * functions will not be called concurrently from multiple threads if the
  * Iterator api is properly used.
  *
  * @define createIteratorFn function that produces an iterator. This function is
  * called as the RecoverableIterator is instantiated, and again after errors
  * are caught.
  *
  * @define recoverErrors partial function that catches expected errors during
  * the iteration (e.g. timeouts).
  *
  * $recoverable
  * @param createIteratorFn $createIteratorFn
  * @param recoverErrors $recoverErrors
  * $threadSafety
  * @todo TODO consider capping the number of recoveries, or delaying recovery after multiple failures
  */
class RecoverableIterator[T](createIteratorFn: => Iterator[T]) // format: OFF
    (recoverErrors: PartialFunction[Throwable, Unit]) 
  extends Iterator[T] 
  with Log { // format: ON
  /** underlying iterator */
  private var currentIterator = createIteratorFn

  private val catchAndRecreateHasNext: PartialFunction[Throwable, Boolean] =
    recoverErrors.andThen{ _ =>
      recreate()
      hasNext
    }

  private val catchAndRecreateNext: PartialFunction[Throwable, T] =
    recoverErrors.andThen{ _ =>
      recreate()
      next()
    }

  override def hasNext: Boolean = {
    try {
      currentIterator.hasNext
    } catch catchAndRecreateHasNext
  }

  override def next(): T = {
    try {
      currentIterator.next()
    } catch catchAndRecreateNext
  }

  private def recreate() {
    currentIterator = createIteratorFn
  }
}
