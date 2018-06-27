use io
use conv

// no name collisions of any kind
class Point {
    x,y: int
    size: int

    initPoint(x0: int, y0: int): Point {
        x = x0
        y = y0
        size = 10
        return this
    }
}

class ColoredPoint extends Point{
//    x,y: int
//    size: int

    initPoint(x0: int, y0: int): ColoredPoint {
        x = x0
        y = y0
        size = 20
        return this
    }
}

main(args: int[][]) {
    p1:Point
    p2:ColoredPoint
    p1 = new Point.initPoint(2, 2)
    p2 = new ColoredPoint.initPoint(2, 2)
    println(unparseInt(p1.size))
    println(unparseInt(p2.size))
}
