// KT-4130 object fields are not evaluated correctly

package foo

class RouterContext(val test: String) {


}

class Route(val segment: String, val parent: Route?, val children: MutableMap<String, Route>, var callback: (RouterContext.() -> Unit)?) {


}

class Router {
    private val routes = Route("/", null, mutableMapOf(), null)

    fun addRoute(path: String, vararg callbacks: RouterContext.() -> Unit) {
        var parent = routes
        var child: Route? = null

        path.split("/").filter { it != "" }.forEach {
            child = Route(it, parent, mutableMapOf(), null)
            parent.children[it] = child!!
            parent = child!!
        }

        child?.callback = callbacks.first()
    }

    fun exec(path: String) {
        val splittedPath = path.split("?")
        val segments = splittedPath.getOrNull(0)
        val queryParams = splittedPath.getOrNull(1)

        var route: Route? = routes

        segments?.split("/")?.filter { it != "" }?.forEach {
            route = route?.children?.get(it)
        }

        val context = RouterContext("you")
        route?.callback?.invoke(context)
    }
}

class Foo() {
    companion object {
        val bar = "Foo.bar ";
        var boo = "FAIL";
        fun baz() = "Foo.baz() "

        fun testImplicitThis(): String {
            boo = "Implicit"
            return baz() + bar + boo
        }
        fun testExplicitThis(): String {
            this.boo = "Explicit"
            return this.baz() + this.bar + this.boo
        }
    }

    val a = bar
    val b = Foo.bar
    val c = baz()
    val d = Foo.baz()
    val e: String
    val f: String

    init {
        e = bar
        f = Foo.bar
        boo = "O"
        Foo.boo += "K"
    }
}

fun box(): String {
    val router = Router()

    router.addRoute("/hello/world", {
        val name = test

        println("Hello $name")
    })

    router.exec("/hello/world")

    assertEquals("Foo.baz() Foo.bar Implicit", Foo.testImplicitThis(), "testImplicitThis")
    assertEquals("Foo.baz() Foo.bar Explicit", Foo.testExplicitThis(), "testExplicitThis")

    val foo = Foo()
    assertEquals("Foo.bar ", foo.a, "foo.a")
    assertEquals("Foo.bar ", foo.b, "foo.b")
    assertEquals("Foo.baz() ", foo.c, "foo.c")
    assertEquals("Foo.baz() ", foo.d, "foo.d")
    assertEquals("Foo.bar ", foo.e, "foo.e")
    assertEquals("Foo.bar ", foo.f, "foo.f")

    assertEquals("OK", Foo.boo, "Foo.boo")
    assertEquals("Foo.bar ", Foo.bar, "Foo.bar")
    assertEquals("Foo.baz() ", Foo.baz(), "Foo.baz()")

    return "OK"
}
