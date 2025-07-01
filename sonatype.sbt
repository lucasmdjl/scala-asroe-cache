
ThisBuild / sonatypeProfileName := "lucasmdjl"
publishMavenStyle := true
licenses := Seq("AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.txt"))

sonatypeProjectHosting := Some(
  xerial.sbt.Sonatype.GitHubHosting(
    "lucasmdjl",
    "scala-asroe-cache",
    "Lucas M. de Jong Larrarte",
    "lucasmdjl@protonmail.com"
  )
)
