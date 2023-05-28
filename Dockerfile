FROM clojure:latest AS base
WORKDIR /app
LABEL maintainer="Bayu Dwiyan Satria <bayudwiyansatria@gmail.com>"

FROM bayudwiyansatria/busybox:latest as build
WORKDIR /app
COPY hermes.jar app.jar

FROM base as relese

COPY --from=build /app /app/*

EXPOSE 80
CMD ["java", "-jar", "app.jar", "-d", "snomed.db", "-p", "80", "--bind-address" , "0.0.0.0",  "serve"]