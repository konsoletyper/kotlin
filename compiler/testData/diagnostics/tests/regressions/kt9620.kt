// KT-9620 AssertionError in checkBounds

interface E1<T : D<!TYPE_ARGUMENTS_NOT_ALLOWED!><String><!>, D>

interface A
interface B
interface D<X>
interface E2<T : D<!TYPE_ARGUMENTS_NOT_ALLOWED!><A><!>, D<!SYNTAX!><<!><!SYNTAX!>B<!><!SYNTAX!>><!><!SYNTAX!>><!>
