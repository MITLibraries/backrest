# backrest - Read-only backport of the REST API for earlier DSpace systems #

[![Build Status](https://travis-ci.org/MITLibraries/backrest.svg?branch=master)]
(https://travis-ci.org/MITLibraries/backrest)

backrest is an API server for the read-only side of the 5.X DSpace REST API.

## Requirements ##

The sole requirement is a Java8 VM on which to run the service (and of course,
a DSpace instance with which to interact). Since backrest and DSpace run on distinct JVMs,
there is no restriction on the java version DSpace uses. However, only DSpace instances
using PostgreSQL as a database are supported. If you wish to build from source,
you will also need the Gradle build tool installed.

## Deployment ##

Since the server is implemented in a framework that utilizes an integrated, embedded web server (Jetty),
no Tomcat (or other container) is needed, nor are there any configuration files to edit. The server thus
has a very small deployment 'footprint': just a jar file to run. But the service does need to know how to
connect to DSpace resources (like the database). We can see how that information can be supplied
by considering some deployment scenarios, from the simplest to the more complex.

### Co-located with DSpace - configuration defaults ###

To run backrest on the same server as DSpace, using only the default configuration values, just download
the backrest release jar, put it in any directory, and launch with a path to the DSpace config on the command-line:

    java -jar backrest-all-<version>.jar /home/dspace/config/dspace.cfg

Backrest will extract all the connection information it needs from dspace.cfg, and your service
should be running on its default port (4567).

### Overriding default configuration ###

In addition to (or instead of) configuration extracted from file data, backrest uses environment variables
to set its operating parameters. In fact, we could have set them individually (_instead_ of using the DSpace config file)
as follows:

    BACKREST_DB_URL=jdbc:postgresql://localhost/dspace
    BACKREST_DB_USER=dspace
    BACKREST_DB_PASSWD=imasecret

In that case we would launch the service even more simply:

    java -jar backrest-all-<version>.jar

By default, the service will listen on port 4567. To change this, set another environment variable:

    BACKREST_SVC_PORT=8080

### Bitstream Access ###

The database holds all the metadata for a DSpace repository, but content files are stored separately. If you just need
metadata exposed by the API, you do not need to specify how to obtain access to the contents (API calls will return 403s).
But if you do, you have a few choices. In the 'co-location' scenario above, backrest can deduce where the assets live, so
you do not need to specify anything further. If not co-located, but the DSpace asset directory is 'reachable' from wherever
the backrest server is running (e.g. you can mount the remote asset store to a server-local filesystem),
then you may set an environment variable to a file URI of the asset store path
(same as the 'assetstore.dir' DSpace config property):

    BACKREST_ASSETS=file:///dspace/assetstore

If the asset directory is *not* reachable, then you may instead chose to _delegate_ access to the DSpace UI itself.
Here, the environment variable is set to the base URL for bitstream access:

    BACKREST_ASSETS=https://dspace.univ.edu/bitstream/handle

In this configuration, backrest will simply fetch the bitstreams from the regular DSpace URLs, and forward to the caller.
This method is really only designed to work for un-restricted content.

## Operation and Management ##

Backrest provides a number of affordances for reliable, performant and scalable operation.
It exposes an endpoint for application monitoring tools such as Nagios:

    http://backrest.my.edu/ping

Ping will always return a 200 'pong' reply if the service is up.

Backrest also gathers internal performance metrics. By default, the data accumulated includes the number and frequency
of API requests and average response time (returned in JSON-formatted reports), but you can (likely only in test-mode)
also capture fine-grained DB query timings. Simply define and expose the 'BACKREST_DB_METRICS' environment variable
to any value for the fine-grained data. The metrics are exposed at:

    http://backrest.my.edu/metrics

Finally, you can enable an endpoint for remote service shutdown. All you need to do is set the environment variable:

    BACKREST_SHUTDOWN_KEY=solong

(you should likely select a less guessable value). If this variable is defined, the service can be remotely shut down
by requesting the URL:

    http://backrest.my.edu/shutdown?key=solong

Best practice is periodically to rotate the key, etc. Remote shutdown is entirely optional, but can be useful in
environments where service provisioning is automated.

## Data Caching ##

Repository data such as found in DSpace is typically quite static: content is gradually added, but existing
content is seldom changed or removed, at least relative to the number of times it is accessed. This comparative
stability means that a data API service can make effective use of cached responses - the cache will not often
need to be invalidated due to change or removal. Data caching is enabled via an environment variable:

    BACKREST_CACHE=1000:d10

where the number before the colon is the maximum number of entries in the cache, and the number
after the colon is the eviction policy (after x units of time and no access, the cache may remove the document).
Both are optional, so the value ':' means a cache of unlimited size and duration.
Possible values for the eviction time units are: _s_(econd), _m_(inute), _h_(our), and _d_(ay).
The cache is managed via the 'cache' endpoint:

    http://backrest.my.edu/cache

where the permitted operations are _GET_ to obtain status about the cache, or _POST_ to send a command to
the cache. The only supported command currently is 'flush', which will empty the entire cache. Also note
that the response cache only manages _documents_ (the XML or JSON responses): bitstreams are not cached.

## Advanced Features - External Service Integration ##

Backrest is completely functional without reliance on any other infrastructure. But if desired, you can
extend its power, maintainability, and ease of use by integrating with other services. These services
must be separately configured, and may impose licensing costs, although the examples chosen generally offer
a 'free tier' of service with some limitations.

### Exception Monitoring ###

Backrest can integrate with the external (SaaS) exception monitoring service Honeybadger <https://www.honeybadger.io>.
A monitoring service will capture all application exceptions (including the full stack trace) to a remote server,
and contact you or your designates for timely remediation. You must create an account, and you will be issued an API key.

NB: this particular service was chosen due to simplicity of integration and a reasonable free tier of service. Others
could easily be substituted, and this does not constitute an endorsement.

Simply add an environment variable with the service-assigned API key:

    HONEYBADGER_API_KEY=34dgkd

### Log Management ###

Cloud-based log management solutions have become popular and offer numerous advantages: they remove the need
to locally manage log files, and provide integrated tools for search and analytics over the log data.
Backrest writes to a small local file that can be 'tailed' to such a service. One such integration is
described here, but many others could be implemented instead.

### External Data Caching ###

Backrest will utilize a Redis-backed cache of the formatted XML or JSON response documents if configured with
the environment variable:

    BACKREST_REDIS_HOST=localhost

You must independently install and configure Redis, but it need not reside on the same server as backrest.
Use of this variable will supersede any local cache, in that the redis cache will be used, not
a local one.

## From the Source ##

It's not likely one would need to customize backrest, since it implements a fixed specification
(the DSpace REST API), but _should_ one wish to extend or alter it, it can easily be built from source. You will
need to have installed the Gradle build tool, and have cloned this repository. Then, to create the executable jar:

    ./gradlew fatJar

is all you need.
