(COMPUNIT
 test
 (FUNC
  a
  (MOVE
   (MEM (ESEQ (MOVE (TEMP i)(CONST 4)) (TEMP i)))
   (ESEQ
    (MOVE (TEMP i)(CONST 5))
    (TEMP j)
   )
  )
 )
)
    
