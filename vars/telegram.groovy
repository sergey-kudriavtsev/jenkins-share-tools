#!/usr/bin/env groovy
/* groovylint-disable DuplicateNumberLiteral, NoJavaUtilDate */

import groovy.json.JsonOutput
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
 * 2) Install your Jenkins HTTP Request Plugin (ID: http_request)
 *
 * 3) Set the next environment variables
 *  <CODE>                   - <REQUIRE> - <SPACE>  - <DESCRIPTION>
 *  TELEGRAM_RUN_NOTIFY      - required  - folder   - Enble run Notify
 *  TELEGRAM_CHAT_ID         - required  - folder   - Id of telegram chat(chanal)
 *                                                      where your bot is admin(can write, edit message)
 *  TELEGRAM_CRED_ID         - required  - global   - Your credential ID (simple text),
 *                                                      where saved bot token(bot can be created before)
 *  TELEGRAM_BUILD_NAME      - required  - pipeline - Your Build Name in notify
 *  TELEGRAM_ADD_NOTIFY_TEXT - optional  - pipeline - Additional text for notify
 *  TIME_ZONE                - optional  - folder   - Your time zone, default UTC
 *  TIME_FORMAT              - optional  - folder   - Your time format, default 'yyyy-MM-dd HH:mm:ss z'
 *
 * 4) Use the method in pipeline how post->always
 *   example:
 *      @Library('jenkins-share-tools@master') _
 *      def notifyBuild() {
 *          telegram.notifyBuild()
 *      }
 *      ...
 *      pipeline {
 *          agent any
 *          stages {
 *              stage('BUILD') {
 *                  ...
 *                  post {
 *                      always { notifyBuild() }
 *                  }
 *              }
 *              ...
 *          }
 *          post {
 *              always { notifyBuild() }
 *          }
 *      }
*/
void notifyBuild() {
    // Exit if not activate
    if (!(env.TELEGRAM_RUN_NOTIFY ? env.TELEGRAM_RUN_NOTIFY.toBoolean() : false))  {
        return
    }
    println 'call telegram.notifyBuild()'

    Date buidTime = Jenkins.instance.getItemByFullName(env.JOB_NAME).getBuildByNumber(env.BUILD_NUMBER as Integer).time
    if (env.TELEGRAM_CONFIG == null) {
        env.with {
            TIME_ZONE   = TIME_ZONE ?: 'UTC'
            TIME_FORMAT = TIME_FORMAT ?: 'yyyy-MM-dd HH:mm:ss z'
        }

        message = [
            id: '',
            method: '/sendMessage',
            inline_keyboard: []

        ]

        config  = [
            status          : 'START',
            anchor          : 0,
            last_duration   : 0,
            git_branch      : this.shX('git symbolic-ref --short HEAD'),
            git_url         : shX('git config --get remote.origin.url').replace('.git', ''),
            git_email       : shX('git log -1 --pretty=format:%ae'),
            git_hash        : shX('git log -1 --pretty=format:%h'),
            git_commit      : shX('git log -1 --pretty=format:%B'),
            git_time        : new Date(Long.valueOf(shX('git log -1 --pretty=format:%ct')) * 1000L),
            build_time      : buidTime,
            message         : message
        ]
        config.git_urlcom        = config.git_url.concat("/commit/${config.git_hash}")
        config.git_url           = config.git_url.concat('/tree/' + config.git_branch)
        config.git_time_format   = config.git_time.format(env.TIME_FORMAT, TimeZone.getTimeZone(env.TIME_ZONE))
        config.build_time_format = config.build_time.format(env.TIME_FORMAT, TimeZone.getTimeZone(env.TIME_ZONE))

        env.TELEGRAM_CONFIG = JsonOutput.toJson(config)
    } else {
        config = readJSON text:env.TELEGRAM_CONFIG
        message = config.message
        message.method  = '/editMessageText'
        // init anchor in log
        config.anchor = config.anchor + 1
        println "<a href=\"#${config.anchor}\"></a>"
    }

    // Stage variables
    build_res       = currentBuild.result
    stage_duration  = Util.getTimeSpanString (
        //   currentBuild.duration - config.last_duration
        System.currentTimeMillis() - currentBuild.startTimeInMillis - config.last_duration
    )

    // Detect reports(Allure,Coverage)
    report_coverage = fileExists(env.WORKSPACE + 'coverage') ? "[*\\[Coverage\\]*](${BUILD_URL}Coverage/)" : ''
    report_alure    = fileExists(env.WORKSPACE_TMP + 'allure-report') ? "[*\\[Allure\\]*](${BUILD_URL}allure/)" : ''
    reports         = (report_coverage || report_alure) ? " \n`Reports:  `${report_alure}  ${report_coverage}" : ''
println 'report_coverage:' + report_coverage
println 'report_coverage:   ' + env.WORKSPACE + 'coverage'
println 'report_coverage:   ' + fileExists(env.WORKSPACE_TMP + 'allure-report')
println 'report_alure:' + report_alure

    // Template message
    mes = templateDefault()

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
        message.inline_keyboard.push([[
            text: env.STAGE_NAME + ' :  ' + build_res + " (${stage_duration})",
            url: BUILD_URL + "consoleText#${config.anchor - 1}"]
        ])
    }

    // Init Json Message
    requestBody = JsonOutput.toJson([
                            chat_id: env.TELEGRAM_CHAT_ID,
                            message_id:message.id,
                            parse_mode: 'MarkdownV2',
                            text:mes,
                            reply_markup:[inline_keyboard:message.inline_keyboard]
                        ])

    // Send message
    result = requestMessage(message.method, requestBody)
    if (result != null) {
        message.id = result.message_id
    }

    // Save current config
    config.last_duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) //currentBuild.duration
    env.TELEGRAM_CONFIG = JsonOutput.toJson(config)
}

/**
* Utility: Request by Bot
*/
Object requestMessage(String metod, String requestBody) {
    Object result = null

    withCredentials([string(credentialsId: env.TELEGRAM_CRED_ID, variable: 'TLEGRAM_TOKEN')]) {
        response =  httpRequest contentType:'APPLICATION_JSON',
                                httpMode: 'POST',
                                requestBody: requestBody,
                                url: 'https://api.telegram.org/bot' + TLEGRAM_TOKEN  + metod

        respons = readJSON(text:response.content)
        if (respons.error_code ) {
            println 'TelegramNotify: The error of sending type message:'.concat(metod.replace('/', ''))
            println 'Error code: '.concat(respons.error_code)
            println 'Descriptions: '.concat(respons.description)
        } else {
            result = respons.result
        }
    }
    return result
}

/**
* Utility: Getting result output of sh instruction
*/
String shX(String script) {
    return sh(returnStdout:true, script:script).trim()
}

/**
* Utility: Getting default template of message
*/
String templateDefault() {
    mes = new StringBuilder()
    def status_ = (( build_res == 'UNSTABLE' ) ? '⚪️' : ((build_res == 'SUCCESS') ? '🔴' : '🔵' ))
    mes .append(" *NEXT\\: ${env.TELEGRAM_BUILD_NAME}*\n")
        .append("${status_}  [*Build:  \\[ \\#${BUILD_NUMBER}\\]*](${BUILD_URL})\n")
        .append("`Time: ${config.build_time_format}`\n")
        .append("`Duration: ${currentBuild.durationString.replace(' and counting', '')}`\n")
        .append("`STATUS:` *${build_res}* ${reports}\n")
        .append('⚙️ `=====================`\n')
        .append("`GIT: ` [*\\[${config.git_branch}: ${config.git_hash}\\]*](${config.git_urlcom})\n")
        .append("`- ${config.git_email}` \n")
        .append("`- ${config.git_time_format}` \n")
        .append("`- ${config.git_commit}` \n")
        .append("${env.TELEGRAM_ADD_NOTIFY_TEXT ?: '`...`'}")

    return mes
}
