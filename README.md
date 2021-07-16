# Dremio

Dremio enables organizations to unlock the value of their data.

## Documentation

Documentation is available at https://docs.dremio.com.

## Quickstart: How to build and run Dremio

### (a) Prerequisites

* JDK 8 (OpenJDK or Oracle)
* (Optional) Maven 3.3.9 or later (using Homebrew: `brew install maven`)

Run the following commands to verify that you have the correct versions of Maven and JDK installed:

    java -version
    mvn --version

### (b) Clone the Repository

    git clone https://github.com/dremio/dremio-oss.git dremio

### (c) Build the Code

    cd dremio
    mvn clean install -DskipTests (or ./mvnw clean install -DskipTests if maven is not installed on the machine)

The "-DskipTests" option skips most of the tests. Running all tests takes a long time.

### (d) Run/Install

#### Run

    distribution/server/target/dremio-community-{DREMIO_VERSION}/dremio-community-{DREMIO_VERSION}/bin/dremio start

OR to start a server with a default user (dremio/dremio123)

    mvn compile exec:exec -pl dac/daemon

Once run, the UI is accessible at:

    http://localhost:9047

#### Production Install

##### (1) Unpack the tarball to install.

    mkdir /opt/dremio
    tar xvzf distribution/server/target/*.tar.gz --strip=1 -C /opt/dremio

##### (2) Start Dremio Embedded Mode

    cd /opt/dremio
    bin/dremio

#### OSS Only

To have the best possible experience with Dremio, we include a number of dependencies when building Dremio that are distributed under non-oss free (as in beer) licenses. 
Examples include drivers for major databases such as Oracle Database, Microsoft SQL Server, MySQL as well as enhancements to improve source pushdowns and thread 
scheduling. If you'd like to only include dependencies with OSS licenses, Dremio will continue to work but some features will be unavailable (such as 
connecting to databases that rely on these drivers). 

To build dremio with only OSS dependencies, you can add the following option to your Maven commandline: `-Ddremio.oss-only=true`

The distribution directory will be `distribution/server/target/dremio-oss-{DREMIO_VERSION}/dremio-oss-{DREMIO_VERSION}`

## First Access

The first time you open Dremio, you will be asked to create an administrator account (unless you started Dremio with the default user). The admin user **must** have the username `dremio`, as that is currently the only user that can have admin privileges.

## Configuring Dremio for Authentication with OAuth2.0

In order to authenticate on Dremio via OAuth2.0, the `services.coordinator.web.auth` property inside the configuration file `dremio.conf` (more info [here](https://docs.dremio.com/advanced-administration/dremio-conf/)) must be updated with the following information:

* authorization URL: the authorization endpoint on your authorization server
* token URL: the token endpoint on your authorization server
* user info URL: the user info endpoint on your authorization server
* callback URL: the URL of your Dremio instance (e.g. http://localhost:9047)
* client ID and client secret: the credentials of the client application configured on your authorization server
* scope: the scope values

As the DigitalHub extended Dremio to allow multitenancy, Dremio accepts usernames with the syntax `<username>@<tenant>`, therefore the configuration includes a further property named `tenantField`. It can be used to specify which user info field stores such information. The property `roleField` can be used to specify which user info field stores the role of the user within the tenant.

Although any OAuth2.0 authentication provider can be used with this extension, the configuration required to use [AAC](https://github.com/scc-digitalhub/AAC) is provided as an example.

### Configuring a Client Application on AAC

On your AAC instance, create a new client app named `dremio` with the following properties:

* Identity providers : `internal`
* Redirect URIs: `<dremio_url>/apiv2/oauth/callback`
* Grant types: `authorization_code`
* Authentication methods: `client_secret_basic, client_secret_post, none`
* Token type: `JWT`
* Selected scopes: `user.roles.me, user.spaces.me, openid, profile, email`

Under "Hooks & Claims", set:

* Unique spaces prefix: `components/dremio`
* Custom claim mapping: `enable`
* Custom claim mapping function (which adds a custom claim holding a single user tenant, as AAC supports users being associated to multiple tenants while Dremio does not; see https://github.com/scc-digitalhub/AAC#53-services-scopes-and-claims):

```
function claimMapping(claims) {
    var valid = ['ROLE_USER'];
    var owner = ['ROLE_OWNER'];
    var prefix = "components/dremio/";

    //fetch username where we find it
    var username = claims["username"];
    if(!username) {
        username = claims ["preferred_username"];
    }
    if(!username) {
        username = claims ["email"];
    }

    if ("roles" in claims && "space" in claims) {
        var space = claims["space"];
        //can't support no space selection performed
        if (Array.isArray(space)) {
            space = null;
        }
        //lookup for policy for selected space
        var tenant = null;
        if(space) {
            for (var role of claims["roles"]) {
                if (role.startsWith(prefix + space + ":")) {
                    var p = role.split(":")[1]
                    
                    //replace owner with USER
                    if (owner.indexOf(p) !== -1) {
                        p = "ROLE_USER"
                    }

                    if (valid.indexOf(p) !== -1) {
                        tenant = space
                        break;
                    }
                }
            }
        }

        if (tenant) {
            tenant = tenant.replace(/\./g,'_');
            claims["dremio/tenant"] = tenant;
            claims["dremio/username"] = username+'@'+tenant;
            claims["dremio/role"] = "admin";
        } 
    }

    return claims;
}
```

### Configuring Dremio

Open your `dremio.conf` file and add the following configuration:

```
services.coordinator.web.auth: {
    type: "oauth",
    oauth: {
        authorizationUrl: "<aac_url>/eauth/authorize"
        tokenUrl: "<aac_url>/oauth/token"
        userInfoUrl: "<aac_url>/userinfo"
        callbackUrl: "<dremio_url>"
        clientId: "<your_client_id>"
        clientSecret: "<your_client_secret>"
        tenantField: "dremio/tenant"
        scope: "openid profile email user.roles.me user.spaces.me"
        roleField: "dremio/role"
    }
}
```

The `tenantField` property matches the claim defined in the function above, which holds the user tenant selected during the login. Dremio will associate it to the username with the syntax `<username>@<tenant>`. That will be used as username in Dremio.

The `roleField` property matches another claim defined in the function, which holds the role of the user (either "user" or "admin") within the selected tenant. Such roles correspond to READ and WRITE privileges over tenant data.

## Multitenancy

The multitenancy model implemented in Dremio is structured as follows:

* admin privileges are not assignable, ADMIN (Dremio admin or system admin) role is reserved to `dremio` user, every other user is assigned either TENANT ADMIN role or USER role
* each user is associated to a single tenant (during the authorization step on AAC, the user will be asked to select which one to use)
* the tenant is attached to the username with the syntax `<username>@<tenant>`
* all APIs accessible to regular users are protected so that non-ADMIN users can only access resources within their own tenant
* when a resource belongs to a tenant (i.e. is shared among all its users), such tenant is specified as a prefix in the resource path with the syntax `<tenant>__<rootname>/path/to/resource`

In Dremio, resources are either containers (spaces, sources, homes) or inside a container (folders, datasets), therefore spaces and sources are prefixed with their tenant, while folders and datasets inherit it from their container, which is the root of their path, and do not need to be prefixed. For example, in the following resource tree, `myspace`, `myfolder` and `mydataset` all belong to `mytenant`:

```
mytenant__myspace
└───myfolder
    └───mydataset
```

The ADMIN user can access any resource. Regular users (i.e. tenant admins and users) can only access resources inside their own home or belonging to their tenant. This implies that users can only query data and access job results according to these constraints.

**NOTE**: currently, when non-ADMIN users create a new source or space (sample sources included), that is **automatically prefixed** with their own tenant. Non-ADMIN users cannot create sources or spaces with a different tenant than their own.

## Additional Changes in the Fork

### Source Management

Differently from the original implementation, in which source management was restricted to ADMIN only, users with TENANT ADMIN role are allowed to manage (create, update and delete) sources in addition to spaces *within their tenant*, while the other users can only manage spaces.

### Arrow Flight and ODBC/JDBC Services

While internal users can use their credentials to connect to Dremio Arrow Flight server endpoint and ODBC and JDBC services, users that log in via OAuth need to set an internal password in order to connect to Dremio with some client. Such password can be set in the Dremio UI on the Account Settings page.

Instructions on how to connect to Dremio via JDBC from **WSO2 Data Services Server** are [included in the fork](https://github.com/scc-digitalhub/dremio-oss/tree/multitenancy/bundle).
