//
//  AppDelegate.swift
//  OpenRemoteApp
//
//  Created by Michael Rademaker on 13/12/2017.
//  Copyright © 2017 OpenRemote. All rights reserved.
//

import UIKit
import ORLib

@UIApplicationMain
class AppDelegate: ORAppDelegate {
    override func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplicationLaunchOptionsKey : Any]?) -> Bool {

        ORServer.hostURL = "example.com"
        ORServer.realm = "example"

        ORAppGroup.entitlement = "group.com.company.example"

        return super.application(application, didFinishLaunchingWithOptions: launchOptions);
    }
}

