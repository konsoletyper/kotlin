package foo

open class A() {
    var x: Int = 0
        get() = field
        set(value) {
            field = value
        }
}

fun box(): String {
    val a = A()
    a.x = 23
    println(a.x)
    return "OK"
}