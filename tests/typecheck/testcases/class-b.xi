use class_a

class B extends A {
    b_ : int
    b() : int { return b_ }
    init() : B {return this}
}

makeA(b_ : int):A {
    b : B = new B.init()
    b.b_ = b_
    return b
}