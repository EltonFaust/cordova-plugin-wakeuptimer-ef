//
//  WakeupPlugin.h
//
//  Created by Brad Kammin on 4/29/14.
//
//

#import <Cordova/CDVPlugin.h>
#import <Cordova/CDVPluginResult.h>

@interface WakeupPlugin : CDVPlugin

- (void)bind:(CDVInvokedUrlCommand*)command;
- (void)wakeup:(CDVInvokedUrlCommand*)command;

@end
