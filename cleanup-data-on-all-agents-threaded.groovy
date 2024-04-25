/*
    Copyright (c) 2015-2024 Sam Gleske - https://github.com/samrocketman/jenkins-script-console-scripts

    Permission is hereby granted, free of charge, to any person obtaining a copy of
    this software and associated documentation files (the "Software"), to deal in
    the Software without restriction, including without limitation the rights to
    use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
    the Software, and to permit persons to whom the Software is furnished to do so,
    subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
    FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
    COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
    IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
    CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
/*
   This script will iterate across all static agents and clean up specified
   directories older than 1 day.

   This script is meant to be run from a job using the System Groovy build
   step.
 */

import hudson.util.RemotingDiagnostics
import java.util.concurrent.locks.ReentrantLock
import jenkins.model.Jenkins

find_command_list = [
    "timeout 1800 find /home/jenkins/.m2 -mtime +1 -type d -name '*-SNAPSHOT' -prune",
    "timeout 1800 find /tmp -maxdepth 1 -mtime +1 -type d \\\\( -name 'tmp[_0-9a-zA-Z]*' -o -name 'npm-*' \\\\) -prune",
    "timeout 1800 find /tmp/phantomjs -maxdepth 1 -mtime +1 -name 'phantomjs-*' -prune"
]

//user configurable variable; set dryRun=false to really delete files
if('DRY_RUN' in build.envVars) {
    dryRun = build.envVars['DRY_RUN']
}
if(!binding.hasVariable('dryRun')) {
    dryRun = true
}
if(dryRun instanceof String) {
    dryRun = (dryRun != "false")
}

if(!dryRun) {
    //find command should run rm on the result to delete said found directories
    find_command_list = find_command_list.collect { it + ' -exec timeout 10 rm -rf {} \\\\;' }
}

//customize withLock and create a script binding lock variable to be shared across threads
ReentrantLock.metaClass.withLock = {
    lock()
    try {
        it()
    } finally {
        unlock()
    }
}
lock = new ReentrantLock()

//create a bash script to be loaded and executed on each agent
bash_script = """
#!/bin/bash
#Generated by a Jenkins job created by Sam Gleske

#delete self after script is run
function cleanup_on() {
  [ -d "\\\${TMPPATH}" ] && rm -rf "\\\${TMPPATH}"
  rm -f "\\\$0"
}
trap cleanup_on EXIT
set -x
#if timeout is not installed, then temporarily create one based on perl
if ! type -P timeout; then
  export TMPPATH="\\\$(mktemp -d)"
  cat > "\\\${TMPPATH}/timeout" <<'EOF'
#!/bin/bash
perl -e 'alarm shift; exec @ARGV' "\\\$@"
EOF
  chmod 755 "\\\${TMPPATH}/timeout"
  export PATH="\\\${TMPPATH}:\\\${PATH}"
fi
${find_command_list.join('\n')}
""".trim()

//prepare to execute the bash script on remote agents
groovy_script = """
script="mktemp /tmp/cleanup-script.shXXX".execute().text
(new File(script)).write '''
${bash_script}
'''.trim()
cmd = ['bash', '-x', script]
println "SCRIPT:"
println "\${new File(script).text}"
println "COMMAND: \${cmd.join(' ')}"
def sout = new StringBuilder(), serr = new StringBuilder()
process = cmd.execute()
process.waitFor()
process.consumeProcessOutput(sout, serr)
println "stdout> \\n\${sout}"
println "stderr> \\n\${serr}"
""".trim()

//kill leftover junk threads to prevent thread leak; in case where postbuild groovy script is not configured
(Thread.getAllStackTraces().keySet() as ArrayList).findAll { it.name.startsWith("agent-cleanup-") }.each { it.stop() }
//execute cleanup script on remote agents in parallel
threads = []
Jenkins.instance.slaves.findAll { it.computer.isOnline() }.each { slave ->
    threads << Thread.start {
        String result = """
            |===================================
            |SLAVE: ${slave.name}
            |DRYRUN: ${dryRun}
            |EXECUTED ON SLAVE:
            |    ${RemotingDiagnostics.executeGroovy(groovy_script, slave.getChannel()).split('\n').join('\n    ')}
            |===================================
            """.stripMargin().trim()
        lock.withLock {
            out.println result
        }
    }
    //make threads easily searchable for cleanup purposes
    threads[-1].name = "agent-cleanup-${slave.name}-${threads[-1].id}"
}
live_threads = threads.findAll { it.isAlive() }
interrupt = null
while(live_threads) {
    lock.withLock {
        println "Waiting on ${live_threads.size()} threads:"
        println "    ${live_threads*.name.join('\n    ')}"
        //run a sleep but abort job if user aborts
        sleep(10000) { e ->
            assert e in InterruptedException
            interrupt = e
        }
    }
    if(interrupt) {
        throw interrupt
    }
    live_threads = threads.findAll { it.isAlive() }
}
threads.each { it.join() }
println '==================================='
println 'CLEANUP FINISHED RUNNING.'
println '==================================='

/* Here's a useful groovy postbuild step; necessary to prevent infinite thread growth
List<Thread> threads = (Thread.getAllStackTraces().keySet() as ArrayList).findAll { it.name.startsWith("agent-cleanup-") }
if(threads) {
    manager.addWarningBadge("${threads.size()} thread(s) hard killed.")
    def summary = manager.createSummary("warning.gif")
    summary.appendText("<h1>${threads.size()} thread(s) hard killed:</h1><pre>${threads*.name*.toString().join('\n')}</pre>", false)
    threads.each { it.stop() }
}
*/
