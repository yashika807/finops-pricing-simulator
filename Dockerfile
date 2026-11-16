FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY src ./src
RUN javac -d out src/*.java

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/out ./out
COPY web ./web
COPY samples ./samples
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "java -Dfinops.home=/app -Djava.awt.headless=true -cp out FinOpsPricingSimulator --web"]
