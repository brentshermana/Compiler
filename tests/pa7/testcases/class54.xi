use io
use conv

// new keyword
class Point {
    x,y: int

    initPoint(x0: int, y0: int): Point {
        x = x0
        y = y0
        return this
    }
}

main(args: int[][]) {
    p1:Point
    p2:Point
    p1 = new Point.initPoint(2, 2)
    p2 = new Point.initPoint(2, 2)
    println(unparseInt(p1.x))
    println(unparseInt(p2.x))
}
