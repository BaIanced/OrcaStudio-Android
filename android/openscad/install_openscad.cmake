# INSTALL_COMMAND of dep_openscad: -DBIN=<build dir> -DSRC=<source dir> -DHEADER=<scad_bridge.h> -DOUT=<out dir> -DSTRIP=<llvm-strip>.
# OUT/lib: libopenscad.so (stripped), OUT/bin: scad_smoke, OUT/include: scad_bridge.h,
# OUT/resources: what OpenSCAD looks up at run time (bundled fonts, MCAD library).
file(REMOVE_RECURSE "${OUT}")
file(MAKE_DIRECTORY "${OUT}/lib" "${OUT}/bin" "${OUT}/include" "${OUT}/resources")
file(COPY "${BIN}/bridge/libopenscad.so" DESTINATION "${OUT}/lib")
file(COPY "${BIN}/bridge/scad_smoke" DESTINATION "${OUT}/bin")
file(COPY "${HEADER}" DESTINATION "${OUT}/include")
execute_process(COMMAND "${STRIP}" --strip-unneeded "${OUT}/lib/libopenscad.so" COMMAND_ERROR_IS_FATAL ANY)
file(COPY "${SRC}/fonts" DESTINATION "${OUT}/resources")
file(COPY "${SRC}/libraries" DESTINATION "${OUT}/resources" PATTERN ".git" EXCLUDE)
file(SIZE "${OUT}/lib/libopenscad.so" size)
message(STATUS "libopenscad.so: ${size} bytes")
