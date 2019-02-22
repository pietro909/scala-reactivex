name := course.value + "-" + assignment.value

scalaVersion := "2.11.12"

scalacOptions ++= Seq("-deprecation")

// grading libraries
libraryDependencies += "junit" % "junit" % "4.10" % Test

// for funsets
libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4"

// from the course's instruction
// https://courses.edx.org/courses/course-v1:EPFLx+scala-reactiveX+1T2019/courseware/116bff35543a4932a43dc6ac3602ce74/6b58a6e07a604020940063c2c358e39b/2?activate_block_id=block-v1%3AEPFLx%2Bscala-reactiveX%2B1T2019%2Btype%40vertical%2Bblock%407ffd3134cb9f44b7a817e106647c112a 
libraryDependencies += "org.scalatest" %% "scalatest" % "1.2.6" % "test"

// include the common dir
commonSourcePackages += "common"

courseId := "bRPXgjY9EeW6RApRXdjJPw"
