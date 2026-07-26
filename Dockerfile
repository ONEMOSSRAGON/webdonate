FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . .

# Render/Railway inject PORT tu dong; Main.java tu doc bien nay
EXPOSE 8080

CMD ["java", "Main.java"]
