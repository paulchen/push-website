FROM gradle:8.10-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build

FROM eclipse-temurin:21
RUN mkdir /opt/app
COPY --from=build /home/gradle/src/build/distributions/push-website-latest.tar /opt/
RUN cd /opt/app && tar xvf ../push-website-latest.tar && rm ../push-website-latest.tar
WORKDIR /opt/app/push-website-latest
CMD ["bin/push-website"]

