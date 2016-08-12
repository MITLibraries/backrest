FROM java:8
MAINTAINER Helen Bailey <http://orcid.org/0000-0002-1881-2045>

ARG br_ver=1.0

RUN wget https://github.com/MITLibraries/backrest/releases/download/v${br_ver}/backrest-all-${br_ver}.jar \
    && mv backrest-all-${br_ver}.jar backrest.jar

EXPOSE 4567

CMD [ "java", "-jar", "backrest.jar" ]
