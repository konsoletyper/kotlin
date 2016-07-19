package foo

open class A(val x: Int = 10) {
    val y = x * 2
    val z: Int

    init {
        z = x * y
    }

    override open fun toString() = "$x:$y"

    open inner class Inner(p: Int, val q: Int = z + p) {
        fun foo(): Int {
            return q + y
        }
    }

    inner class Inner2() : Inner(22)
}

class B : A() {
    override fun toString() = "$z"
}

object C : A() {
    override fun toString() = "obj($z)"
}

fun test(x: Int): Int {
    class QQQ() {
        inner class PPP() {
            fun bzz() = x
        }
    }
    return QQQ().PPP().bzz()
}

fun box(): String {
    var x = 0
    println(x++)

    println(A(10).Inner(20).foo())
    println(A(10).Inner2().foo())

    println(A(23).toString() + ", " + B() + ", " + C + ", " + C.y)
    println(test(555))

    return "OK"
}