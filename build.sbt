ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val grackle_0_0_49 = (project in file("grackle_0_0_49"))
  .settings(
    name := "grackle-0-0-49",
    libraryDependencies ++= Seq(
      "edu.gemini" %% "gsp-graphql-core" % "0.0.49",
      "edu.gemini" %% "gsp-graphql-doobie" % "0.0.49",
      "edu.gemini" %% "gsp-graphql-generic" % "0.0.49",
      "org.tpolecat" %% "doobie-core" % "0.13.4",
      "org.tpolecat" %% "doobie-h2" % "0.13.4",
      "org.tpolecat" %% "doobie-hikari" % "0.13.4",
      "org.tpolecat" %% "doobie-postgres" % "0.13.4"
    )
  )

lazy val grackle_0_1_0 = (project in file("grackle_0_1_0"))
  .settings(
    name := "grackle-0-1-0",
    libraryDependencies ++= Seq(
      "edu.gemini" %% "gsp-graphql-core" % "0.1.0",
      "edu.gemini" %% "gsp-graphql-doobie" % "0.1.0",
      "edu.gemini" %% "gsp-graphql-generic" % "0.1.0"
    )
  )