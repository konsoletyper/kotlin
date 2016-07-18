package foo

open class A(val x: Int = 10) {
    val y = x * 2
    val z: Int

    init {
        z = x * y
    }

    override open fun toString() = "$x:$y"
}

class B : A() {
    override fun toString() = "$z"
}

fun box(): String {
    return A(23).toString() + ", " + B().toString()
}