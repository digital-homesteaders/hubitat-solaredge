/**
 * Compile every app and driver against the hub stubs and run its top-level
 * code (definition, metadata, preferences). Then check that each method a
 * driver calls on its parent exists there and is not private, since Hubitat
 * refuses calls to private methods across objects, and the same for the
 * methods the app calls on its child devices.
 */

import java.lang.reflect.Modifier
import static Check.check

File root = new File(getClass().protectionDomain.codeSource.location.path).parentFile.parentFile
Hub hub = new Hub(root)
List<File> files = (new File(root, "apps").listFiles() + new File(root, "drivers").listFiles())
    .findAll { it.name.endsWith(".groovy") }.sort { it.path }

files.each { File f ->
    check("compiles and loads: ${root.toPath().relativize(f.toPath())}") {
        HubitatScript s = hub.instance(f)
        assert s.callsNamed("definition"), "no definition(...)"
    }
}

// A script method named like a metadata/page DSL word may shadow the DSL.
List<String> dslWords = ["definition", "capability", "attribute", "command", "fingerprint", "input",
    "section", "page", "paragraph", "href", "label", "mode", "app", "get"]
files.each { File f ->
    check("no method shadows a definition word: ${f.name}") {
        Class c = hub.compile(f)
        List<String> clash = c.declaredMethods*.name.unique().findAll { it in dslWords }
        assert !clash
    }
}

boolean isPublic(Class c, String name) {
    c.methods.any { it.name == name && !Modifier.isPrivate(it.modifiers) }
}

Class app = hub.compile(new File(root, "apps/solaredge-connect.groovy"))
new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { File f ->
    List<String> called = (f.text =~ /parent\??\.(\w+)\s*\(/).collect { it[1] }.unique()
    check("parent methods called by ${f.name} exist and are public") {
        assert called
        List<String> missing = called.findAll { !isPublic(app, it) }
        assert !missing
    }
}

check("child device methods called by the app exist and are public in every driver") {
    String text = new File(root, "apps/solaredge-connect.groovy").text
    List<String> builtIn = ["getDataValue", "updateDataValue", "currentValue", "getDeviceNetworkId", "getDisplayName"]
    List<String> called = (text =~ /\bcd\??\.(\w+)\s*\(/).collect { it[1] }.unique() - builtIn
    assert called.sort() == ["applyEvents", "clearForecast"]
    new File(root, "drivers").listFiles().findAll { it.name.endsWith(".groovy") }.each { File f ->
        assert isPublic(hub.compile(f), "applyEvents"), f.name
    }
    // clearForecast is only called on inverters.
    assert isPublic(hub.compile(new File(root, "drivers/solaredge-inverter.groovy")), "clearForecast")
}

check("every driver the app creates exists") {
    String text = new File(root, "apps/solaredge-connect.groovy").text
    String block = text.substring(text.indexOf("Map DRIVERS = ["), text.indexOf("]", text.indexOf("Map DRIVERS = [")))
    List<String> names = (block =~ /(\w+): "(SolarEdge [^"]+)"/).collect { it[2] }
    assert names.size() == 5
    names.each { assert hub.driverFiles[it], "no driver named ${it}" }
}

check("no log line touches the credentials or the login body") {
    String text = new File(root, "apps/solaredge-connect.groovy").text
    List<String> logLines = text.readLines().findAll { it =~ /log\.(debug|info|warn|error)|logDebug|logOnce/ }
    assert logLines.size() > 10
    assert !logLines.any { it =~ /username|password|j_password|\$\{?body/ }
}

Check.finish()
