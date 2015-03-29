# Introduction #

This page describes how one can generate Eclipse project using [SBT](http://www.scala-sbt.org/) build tool.


# Details #

  * First [install SBT](http://www.scala-sbt.org/release/docs/Getting-Started/Setup.html) of version 0.12.x.
  * Change current directory to the project's home and run sbt eclipse
```
>s3index$ sbt eclipse
[info] Loading project definition from /home/project/S3Index/src/s3index/project
[info] Set current project to s3index (in build file:/home/project/S3Index/src/s3index/)
[info] About to create Eclipse project files for your project(s).
[info] Successfully created Eclipse project files for project(s):
[info] s3index
```
  * In Eclipse use the _Import Wizard_ to import _Existing Projects into Workspace_.