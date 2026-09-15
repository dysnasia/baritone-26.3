FROM eclipse-temurin:25-jdk

WORKDIR /code
COPY . /code

RUN ./gradlew build
