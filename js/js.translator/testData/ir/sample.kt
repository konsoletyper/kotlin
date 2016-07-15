package foo

class A(val x: Int = 10) {
    val y = x * 2
    val z: Int

    init {
        z = x * y
    }

    override fun toString() = "$x:$y"
}

fun box(): String {
    return A(23).toString()
}