(COMPUNIT
 test
 (FUNC
  a
  (SEQ
   (MOVE (TEMP i) (TEMP _ARG0))
   (MOVE (TEMP j) (TEMP _ARG1))
   (RETURN (TEMP i) (MUL (CONST 2) (TEMP j)))))
 (FUNC
  b
  (SEQ
   (MOVE (TEMP i) (TEMP _ARG0))
   (MOVE (TEMP j) (TEMP _ARG1))
   (MOVE (TEMP x) (CALL (NAME a) (TEMP i) (TEMP j)))
   (MOVE (TEMP y) (TEMP _RET1))
   (RETURN (ADD (TEMP x) (MUL (CONST 5) (TEMP y)))))))
