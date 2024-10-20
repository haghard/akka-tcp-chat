addSbtPlugin("org.scalameta"    % "sbt-scalafmt"  % "2.5.2")
addSbtPlugin("com.scalapenos"   % "sbt-prompt"    % "1.0.2")


addSbtPlugin("com.timushev.sbt"        % "sbt-rewarn"          %  "0.1.3")
addSbtPlugin("ch.epfl.scala"           % "sbt-scalafix"        %  "0.13.0")
addSbtPlugin("com.eed3si9n"            % "sbt-buildinfo"       %  "0.11.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.10.2" cross CrossVersion.full)

addDependencyTreePlugin