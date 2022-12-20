# Setting up a SNOMED CT Terminology Server using Hermes



First, Clone [https://github.com/wardle/hermes](https://github.com/wardle/hermes)  into a folder called `hermes`. Technically, you only need `Dockerfile` and `hermes.jar`

Now, extract the SNOMED CT release files into a folder called `snomed` inside the `hermes` and directory.

https://drive.google.com/drive/folders/1wPQoGYEv_Ua1Er7yRWm5BfwlNmhNJbye?usp=sharing

**Dockerfile** 

```
FROM clojure
RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
COPY . /usr/src/app
ENV HOST 0.0.0.0
CMD ["java", "-jar", "hermes.jar", "-d", "snomed.db", "-p", "8080", "--bind-address" , "0.0.0.0",  "serve"]
```

hermes.jar

https://drive.google.com/file/d/1gCZxf73l65Xc4tboeRITpJKx7NTkLwYD/view?usp=sharing

Your hermes directory should look something like this:

```
hermes
├── snomed 
│   │   
│   └── SnomedCT_InternationalRF2_PRODUCTION_20221031T120000Z
├── Dockerfile
├── LICENSE
├── README.md
├── deps.edn
├── resources
├── src
└── test
└── hermes.jar
```

Import like this :

```
clj -M:run --db snomed.db import snomed/SnomedCT_InternationalRF2_PRODUCTION_20221031T120000Z
```

Index and compact

```
clj -M:run --db snomed.db index
clj -M:run --db snomed.db compact
```

Build the apps with Dockerfile

```
docker build -t hermes:latest .
```

Run a server!

```
docker run -p 8080:8080 hermes:latest
```

##### Obtaining information about a SNOMED-CT concept 

You may now use the web-service from your code.

```
curl http://127.0.0.1:8080/v1/snomed/concepts/24700007
```

Return JSON

```
{
  "active": true,
  "definitionStatusId": 900000000000074008,
  "effectiveTime": "2002-01-31",
  "id": 24700007,
  "moduleId": 900000000000207008
}
```

