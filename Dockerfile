# Build Stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy source code and compile
COPY src/ ./src/
RUN mkdir -p out && javac -d out src/ParkingWebPrototype.java src/Main.java

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy compiled classes from builder
COPY --from=builder /app/out ./out

# Default port configuration for container
EXPOSE 8080
ENV PORT=8080

# Execute the application
CMD ["java", "-cp", "out", "ParkingWebPrototype"]
