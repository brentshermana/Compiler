use io

main(argv:int[][]) {
    x : int = 0
    while (x < 5) {
        x = x + 1
        break
    }
    while (x < 5) {
        x = x + 1
        continue
    }
}