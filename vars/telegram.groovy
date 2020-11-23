#!/usr/bin/env groovy
import groovy.json.*
import hudson.Util

/** 
 * Notify method of The Build status in The Telegram chat using a bot
 * @autor Kudriavtsev Sergey
 * @version 0.0.1
 * 
 * Instruction
 * 1) Don't use "Groovy Sandbox" or approve the following methods in https://<Jenkins URL>/scriptApproval/
 * method groovy.lang.GroovyObject invokeMethod java.lang.String java.lang.Object
 * method hudson.model.Job getBuildByNumber int
 * method hudson.model.Run getTime
 * method jenkins.model.Jenkins getItemByFullName java.lang.String
 * new groovy.json.JsonSlurperClassic
 * new java.io.File java.lang.String
 * staticMethod hudson.Util getTimeSpanString long
 * staticMethod java.lang.Long valueOf java.lang.String
 * staticMethod java.nio.file.Paths get java.lang.String java.lang.String[]
 * staticMethod jenkins.model.Jenkins getInstance
 * 
 * 2) Set the next environment variables
 *  <CODE>                   - <REQUIRE> - <SPACE>  - <DESCRIPTION>
 *  TELEGRAM_RUN_NOTIFY      - required  - folder   - Enble run Notify
 *  TELEGRAM_CHAT_ID         - required  - folder   - Id of telegram chat(chanal) where your bot is admin(can write, edit message)
 *  TELEGRAM_CRED_ID         - required  - global   - Your credential ID (simple text), where saved bot token(bot can be created before)
 *  TELEGRAM_BUILD_NAME      - required  - pipeline - Your Build Name in notify
 *  TELEGRAM_ADD_NOTIFY_TEXT - optional  - pipeline - Additional text for notify
 *  TIME_ZONE                - optional  - folder   - Your time zone, default UTC
 *  TIME_FORMAT              - optional  - folder   - Your time format, default 'yyyy-MM-dd HH:mm:ss z'
 * 
 * 3) Use the method in pipeline how post->always
 *   example:
 *  
 *      load 'bot-telegram.groovy'
 *      pipeline {
 *          agent any
 *          stages {
 *              stage('BUILD') {
 *                  ...
 *                  post { 
 *                      always { telegram.notifyBuild() }
 *                  }
 *              }
 *              ...
 *          }
 *          post { 
 *              always { telegram.notifyBuild() }
 *          }
 *      }
 * 
*/
def notifyBuild() {
    // Exit if not activate
    if (!(env.TELEGRAM_RUN_NOTIFY != null && env.TELEGRAM_RUN_NOTIFY.toBoolean()) ) { 
        return
    }

    // Init
    def config = [:]
    def message     = [:]
    if (!(env.TELEGRAM_CONFIG != null)) {
        env.TIME_ZONE           = env.TIME_ZONE ?: 'UTC'
        env.TIME_FORMAT         = env.TIME_FORMAT ?: 'yyyy-MM-dd HH:mm:ss z'

        config['status']        = 'START'
        config['anchor']        = 0
        config['last_duration'] = 0
        message['method']       = '/sendMessage'
        message['id']           = ''
        message['inline_keyboard']     = []
        config['message']       = message
        config['git_branch']    = sh(returnStdout: true, script: "git symbolic-ref --short HEAD").trim()
        config['git_url']       = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
        config.git_url          = config.git_url.replace('.git',"/tree/${config.git_branch}")
        config['git_email']     = sh(returnStdout: true, script: "git log -1 --pretty=format:'%ae'").trim()
        config['git_hash']      = sh(returnStdout: true, script: "git log -1 --pretty=format:'%h'").trim()
        config['git_time']      = new Date(Long.valueOf(sh(returnStdout: true, script: "git log -1 --pretty=format:'%ct'").trim()) * 1000L)
        config['git_time_format'] = config.git_time.format(env.TIME_FORMAT, TimeZone.getTimeZone(env.TIME_ZONE))
        config['git_commit']    = sh(returnStdout: true, script: "git log -1 --pretty=format:'%B'").trim()
        config['git_urlcom']    = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
        config.git_urlcom       = config.git_urlcom.replace('.git',"/commit/${config.git_hash}")
        config['build_time']    = Jenkins.getInstance().getItemByFullName(env.JOB_NAME).getBuildByNumber(env.BUILD_NUMBER as Integer).getTime()
        config['build_time_format'] = config.build_time.format(env.TIME_FORMAT, TimeZone.getTimeZone(env.TIME_ZONE))
        env.TELEGRAM_CONFIG = JsonOutput.toJson(config)
    } else {
        config = readJSON text:env.TELEGRAM_CONFIG
        message = config['message']
        message.method  = '/editMessageText'
        // init anchor in log
        config.anchor = config.anchor +1
        println "<a href=\"#${config.anchor}\"></a>"
    }
    
    // Stage variables
    def build_res       = currentBuild.result
    def stage_duration  = Util.getTimeSpanString((System.currentTimeMillis() - currentBuild.startTimeInMillis) - config.last_duration) //currentBuild.duration - config.last_duration
    
    // Detect reports(Allure,Coverage) 
    def report_coverage = !fileExists('Coverage') ? '': "[*\\[Coverage\\]*](${BUILD_URL}Coverage/)"
    def report_alure    = !fileExists('allure') ? '': "[*\\[Allure\\]*](${BUILD_URL}allure/)"
    def reports         = !(report_coverage || report_alure) ? '' : " \n`Reports:  ${report_alure}  ${report_coverage}`"

    
    // Template message
    def mes = (
       // "[__*NEXT\\: Admin portal*__](${BUILD_URL})" +
        " *NEXT\\: ${env.TELEGRAM_BUILD_NAME}*\n" +
        "[*Build:  \\[ \\#${BUILD_NUMBER}\\]*](${BUILD_URL})\n" +
      //  "`[Build:#${BUILD_NUMBER}]`\n" +
        "`Time: ${config.build_time_format}`\n" +
        "`Duration: ${currentBuild.durationString.minus(' and counting')}`\n" +
        "`STATUS:` *${build_res}* ${reports}\n" +
        "`=========================`\n" +
        "`GIT: ` [*\\[${config.git_branch}: ${config.git_hash}\\]*](${config.git_urlcom})\n" +
        "`- ${config.git_email}` \n" +
        "`- ${config.git_time_format}` \n" +
        "`- ${config.git_commit}` \n" +
        "`${env.TELEGRAM_ADD_NOTIFY_TEXT||'...'}`").stripIndent()

    // Injection of Buttons-stage at inline message
    if ( env.STAGE_NAME == 'Declarative: Post Actions') {
        // Delete all stage from the current build in message
        if ( build_res == 'SUCCESS') {
            message.inline_keyboard = [[]]
        } else {
            // Leaving only error Buttons-stage
            message.inline_keyboard = [message.inline_keyboard.last()]
        }
        
    } else {
        // Add current stage name
        message.inline_keyboard.push([[text:env.STAGE_NAME + ' :  ' + build_res + " (${stage_duration})", url: BUILD_URL + "consoleText#${config.anchor-1}"]])
    }
    
    // Init Json Message
    def requestBody = JsonOutput.toJson([
                            chat_id: env.TELEGRAM_CHAT_ID, 
                            message_id:message.id, 
                            parse_mode: 'MarkdownV2', 
                            text:mes, 
                            reply_markup:[inline_keyboard:message.inline_keyboard]
                        ]) 

    // Send message
    withCredentials([string(credentialsId: env.TELEGRAM_CRED_ID, variable: 'TLEGRAM_TOKEN')]) {
        def response =  httpRequest contentType:'APPLICATION_JSON', 
                            httpMode: 'POST', 
                            requestBody: requestBody, 
                            url: 'https://api.telegram.org/bot' + TLEGRAM_TOKEN  + message.method
        // Save arguments
        def props = readJSON text:response.content
        message.id = props.result.message_id
        config.last_duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) //currentBuild.duration
        
        // Save current config
        env.TELEGRAM_CONFIG = JsonOutput.toJson(config)
    }
    
}
