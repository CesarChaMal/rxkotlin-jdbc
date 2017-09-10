package org.nield.rxkotlinjdbc

import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import java.io.InputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

fun Connection.execute(sql: String, vararg v: Any?) = Single.fromCallable {
    prepareStatement(sql).let {
        it.processParameters(v)
        it.executeUpdate()
        it.updateCount
    }
}

fun Connection.select(sql: String, vararg v: Any?) = {
    prepareStatement(sql).let { ps ->
        ps.processParameters(v)
        QueryState({ps.executeQuery()}, ps)
    }
}

fun Connection.insert(insertSQL: String, vararg v: Any?) =  {
    val ps = prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)
    ps.processParameters(v)
    ps.executeUpdate()
    QueryState({ps.generatedKeys}, ps)
}

fun DataSource.execute(sql: String, vararg v: Any?): Int {
    val c = connection
    val ps = connection.prepareStatement(sql)
    ps.processParameters(v)
    val affectedCount = ps.executeUpdate()
    ps.close()
    c.close()
    return affectedCount
}

fun DataSource.select(sql: String, vararg v: Any?) = {
    connection.let { connection ->
        val ps = connection.prepareStatement(sql)
        ps.processParameters(v)
        QueryState({ ps.executeQuery() }, ps, connection)
    }
}


fun DataSource.insert(insertSQL: String, vararg v: Any?) = {
    val connection = this.connection
    val ps = connection.prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)
    ps.processParameters(v)
    ps.executeUpdate()
    QueryState({ps.generatedKeys}, ps)
}

fun <T: Any> (() -> QueryState).toObservable(mapper: (ResultSet) -> T) = Observable.defer {
    this().toObservable(mapper)
}

fun <T: Any> (() -> QueryState).toFlowable(mapper: (ResultSet) -> T) = Flowable.defer {
    this().toFlowable(mapper)
}

fun <T: Any> (() -> QueryState).toSingle(mapper: (ResultSet) -> T) = Single.defer {
    toObservable(mapper).singleOrError()
}

fun <T: Any> (() -> QueryState).toMaybe(mapper: (ResultSet) -> T) = Maybe.defer {
    toObservable(mapper).singleElement()
}

fun <T: Any> (() -> QueryState).toSequence(mapper: (ResultSet) -> T) =
        toObservable(mapper).blockingIterable().asSequence()

class QueryState(
        val resultSetGetter: () -> ResultSet,
        val statement: PreparedStatement? = null,
        val connection: Connection? = null
) {
    fun <T: Any> toObservable(mapper: (ResultSet) -> T): Observable<T> {

        return Observable.defer {
            val iterator = QueryIterator(this, resultSetGetter(), mapper)
            Observable.fromIterable(iterator.asIterable())
                    .doOnTerminate { iterator.close() }
                    .doOnDispose { iterator.close() }
        }
    }

    fun <T: Any> toFlowable(mapper: (ResultSet) -> T): Flowable<T> {
        return Flowable.defer {
            val iterator = QueryIterator(this, resultSetGetter(), mapper)
            Flowable.fromIterable(iterator.asIterable())
                    .doOnTerminate { iterator.close() }
                    .doOnCancel { iterator.cancel() }
        }
    }
}

class QueryIterator<out T>(val qs: QueryState,
                           val rs: ResultSet,
                           val mapper: (ResultSet) -> T
) : Iterator<T> {

    private var didNext = false
    private var hasNext = false
    private val cancelled = AtomicBoolean(false)

    override fun next(): T {
        if (!didNext) {
            rs.next()
        }
        didNext = false
        return mapper(rs)
    }

    override fun hasNext(): Boolean {
        if (cancelled.get()) {
            excecuteCancel()
            hasNext = false
            return false
        }
        if (!didNext) {
            hasNext = rs.next()
            didNext = true
        }
        return hasNext
    }

    fun asIterable() = object: Iterable<T> {
        override fun iterator(): Iterator<T> = this@QueryIterator
    }

    fun close() {
        rs.close()
        qs.statement?.close()
        qs.connection?.close()
    }
    fun cancel() {
        cancelled.set(true)
    }
    private fun excecuteCancel() {
        rs.close()
        qs.statement?.close()
        qs.connection?.close()
    }
}

fun PreparedStatement.processParameters(v: Array<out Any?>) = v.forEachIndexed { pos, argVal ->
    when (argVal) {
        null -> setObject(pos+1, null)
        is UUID -> setObject(pos+1, argVal)
        is Int -> setInt(pos+1, argVal)
        is String -> setString(pos+1, argVal)
        is Double -> setDouble(pos+1, argVal)
        is Boolean -> setBoolean(pos+1, argVal)
        is Float -> setFloat(pos+1, argVal)
        is Long -> setLong(pos+1, argVal)
        is LocalTime -> setTime(pos+1, java.sql.Time.valueOf(argVal))
        is LocalDate -> setDate(pos+1, java.sql.Date.valueOf(argVal))
        is LocalDateTime -> setTimestamp(pos+1, java.sql.Timestamp.valueOf(argVal))
        is BigDecimal -> setBigDecimal(pos+1, argVal)
        is InputStream -> setBinaryStream(pos+1, argVal)
        is Enum<*> -> setObject(pos+1, argVal)
    }
}
