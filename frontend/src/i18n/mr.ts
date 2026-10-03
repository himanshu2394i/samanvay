import type { Dictionary } from './types'

// Marathi (मराठी) strings for the citizen surface. Keys mirror `en.ts` exactly (the
// `Dictionary` type enforces this). Translations are natural, official Marathi in
// Devanagari — not transliterations — kept concise for a government audience.
export const mr: Dictionary = {
  // --- language toggle ---------------------------------------------------------------------
  'language.label': 'भाषा',
  'language.english': 'English',
  'language.marathi': 'मराठी',
  'language.englishAria': 'इंग्रजी',
  'language.marathiAria': 'मराठी',

  // --- layout ------------------------------------------------------------------------------
  'layout.skipToMain': 'मुख्य मजकुराकडे जा',
  'layout.govOfMaharashtra': 'महाराष्ट्र शासन',
  'layout.demoBuild': 'प्रात्यक्षिक आवृत्ती',
  'layout.citizenServices': 'नागरिक सेवा',
  'layout.navMain': 'मुख्य',
  'layout.signOut': 'बाहेर पडा',
  'layout.signIn': 'साइन इन करा',
  'layout.footerConsent':
    'समन्वय तुमच्या संमतीनेच तुम्हाला शासकीय विभागांशी जोडते. विकास आवृत्ती: विभाग आणि साइन-इन पुरवठादार हे प्रात्यक्षिक आहेत.',
  'layout.footerStaffLink': 'कर्मचारी कन्सोल',
  'layout.footerStaffRest': '(अधिकारी, परीक्षक आणि प्रशासक स्वतंत्रपणे साइन इन करतात)',

  // --- navigation --------------------------------------------------------------------------
  'nav.services': 'सेवा',
  'nav.applications': 'माझे अर्ज',
  'nav.consents': 'माझ्या संमती',
  'nav.profile': 'माझी माहिती',

  // --- landing page ------------------------------------------------------------------------
  'landing.eyebrow': 'महाराष्ट्र शासन · नागरिक सेवा',
  'landing.title': 'कागदपत्रे न बाळगता शासकीय सेवांसाठी अर्ज करा',
  'landing.lede':
    'समन्वय तुमच्या होकारानंतरच, सेवेसाठी आवश्यक असलेले रेकॉर्ड ते जिथे आहेत त्या विभागांकडून थेट मिळवते.',
  'landing.browseServices': 'सेवा पाहा',
  'landing.signInToStart': 'सुरू करण्यासाठी साइन इन करा',
  'landing.signInHint':
    'तुमच्या ईमेल आणि पासवर्डने किंवा पासकीने साइन इन करा. नवीन आहात? साइन-इन पानावर नोंदणी करा निवडा.',
  'landing.assure.title': 'तुमची रेकॉर्ड जिथे आहेत तिथेच राहतात',
  'landing.assure.point1': 'तुम्ही संमती दिल्यानंतरच मिळवली जातात — त्याआधी कधीही नाही.',
  'landing.assure.point2': 'समन्वय ती साठवत नाही; विभागच त्यांचा स्रोत राहतो.',
  'landing.assure.point3': 'तुम्ही कधीही संमती मागे घेऊ शकता.',
  'landing.howItWorks': 'हे कसे चालते',
  'landing.step1.title': 'सेवा पाहा',
  'landing.step1.text': 'सुरू करण्यापूर्वी प्रत्येक शासकीय सेवेला काय आवश्यक आहे ते पाहा.',
  'landing.step2.title': 'विभागांची खाती जोडा',
  'landing.step2.text': 'तुमची रेकॉर्ड ठेवणारे विभाग एकदाच जोडा.',
  'landing.step3.title': 'संमती द्या',
  'landing.step3.text': 'कोणती रेकॉर्ड आणि कोणत्या हेतूसाठी मिळवायची ते नेमके सांगा.',
  'landing.step4.title': 'सादर करा',
  'landing.step4.text': 'आम्ही तुमच्यासाठी रेकॉर्ड मिळवतो: कोणतीही कागदपत्रे अपलोड करावी लागत नाहीत.',
  'landing.step5.title': 'माग ठेवा',
  'landing.step5.text': 'तुमच्या अर्जावर निर्णय होईपर्यंत प्रत्येक विभागाची तपासणी पाहत राहा.',

  // --- services list -----------------------------------------------------------------------
  'services.title': 'सेवा',
  'services.lede': 'सेवेला काय आवश्यक आहे ते पाहण्यासाठी आणि अर्ज करण्यासाठी एक सेवा निवडा.',
  'services.loading': 'सेवा लोड होत आहेत',
  'services.none': 'सध्या अर्जांसाठी कोणतीही सेवा खुली नाही.',
  'services.recordsNeeded': 'आवश्यक रेकॉर्ड: {categories}.',
  'services.decisionTarget': 'निर्णयाचे उद्दिष्ट: {hours} तासांच्या आत.',
  'services.viewDetails': 'तपशील पाहा',

  // --- single service ----------------------------------------------------------------------
  'service.loading': 'सेवा लोड होत आहे',
  'service.allServices': 'सर्व सेवा',
  'service.whatWeFetch': 'आम्ही काय आणि कोणाकडून मिळवू',
  'service.consentPurposePrefix': 'या हेतूसाठी तुमची संमती विचारली जाईल:',
  'service.consentSla': 'तुमच्या अर्जावर {hours} तासांच्या आत निर्णय होण्याचे उद्दिष्ट आहे.',
  'service.apply': 'या सेवेसाठी अर्ज करा',

  // --- apply wizard ------------------------------------------------------------------------
  'apply.loading': 'सेवा लोड होत आहे',
  'apply.backToService': 'सेवेच्या तपशीलाकडे परत जा',
  'apply.title': 'अर्ज: {name}',
  'apply.stepsLabel': 'अर्जाचे टप्पे',
  'apply.step.connect': 'खाती जोडा',
  'apply.step.consent': 'संमती द्या',
  'apply.step.submit': 'सादर करा',

  'connect.heading': 'तुमच्या विभागांची खाती जोडा',
  'connect.introOne': 'या सेवेला {count} विभागाकडून रेकॉर्ड आवश्यक आहे. खाते तुमचे आहे हे सिद्ध करून ते एकदाच जोडा.',
  'connect.introMany':
    'या सेवेला {count} विभागांकडून रेकॉर्ड आवश्यक आहेत. खाते तुमचे आहे हे सिद्ध करून प्रत्येक विभाग एकदाच जोडा.',
  'connect.checking': 'तुमची जोडलेली खाती तपासत आहोत',
  'connect.connectedCount': '{total} पैकी {linked} जोडली',
  'connect.continue': 'संमतीकडे जा',
  'connect.connectAllHint': 'पुढे जाण्यासाठी वरील प्रत्येक विभाग जोडा.',

  'dept.connected': 'जोडले',
  'dept.notConnected': 'जोडलेले नाही',
  'dept.provides': 'पुरवते: {categories}.',
  'dept.noProvider': 'हे खाते जोडण्याचा समर्थित मार्ग सध्या उपलब्ध नाही.',
  'dept.offeredByServer': '(सर्व्हरने देऊ केलेले: {providers}.)',
  'dept.howProve': 'तुम्हाला ते कसे सिद्ध करायचे आहे?',
  'dept.idType': 'ओळखपत्राचा प्रकार',
  'dept.idTypeHint': 'उदाहरणार्थ RATION, किंवा विभागाचे नाव.',
  'dept.yourId': 'या विभागाकडील तुमचे ओळखपत्र',
  'dept.oneTimeCode': 'एकवेळचा कोड',
  'dept.otpHint': 'विकास आवृत्ती: प्रात्यक्षिक कोड 000000 आहे.',
  'dept.connecting': 'जोडत आहोत…',
  'dept.connect': '{name} जोडा',

  'consent.heading': 'तुमची संमती द्या',
  'consent.grantedPrefix': 'संमती दिली. ती {date} पर्यंत वैध आहे आणि तुम्ही ती कधीही येथून मागे घेऊ शकता',
  'consent.grantedSuffix': '.',
  'consent.askingSuffix': 'तुमच्याबद्दलचे हे रेकॉर्ड मिळवण्याची विनंती करत आहे:',
  'consent.purpose': 'हेतू:',
  'consent.granting': 'संमती देत आहोत…',
  'consent.agree': 'मी सहमत आहे: संमती द्या',
  'consent.reviewIntro':
    'काहीही मिळवण्यापूर्वी, कोण विचारत आहे, कोणती रेकॉर्ड आणि का हे आम्ही तुम्हाला नेमके दाखवू. तुमच्या सहमतीशिवाय काहीही सामायिक केले जात नाही.',
  'consent.preparing': 'तयार करत आहोत…',
  'consent.review': 'काय सामायिक केले जाईल ते पाहा',
  'consent.back': 'मागे',
  'consent.continue': 'सादर करण्याकडे जा',

  'submit.heading': 'तुमचा अर्ज सादर करा',
  'submit.introPrefix': 'तुम्ही सामायिक करण्यास सहमती दिलेली रेकॉर्ड आम्ही आता मिळवू आणि यासाठी तपासणी सुरू करू',
  'submit.introSuffix': '.',
  'submit.slowPrefix': 'तुमचा अर्ज सादर झाला आहे, पण त्याचा क्रमांक अजून तयार नाही. थोड्या वेळाने',
  'submit.slowSuffix': 'तपासा.',
  'submit.back': 'मागे',
  'submit.submitting': 'सादर करत आहोत…',
  'submit.checkNumber': 'माझ्या अर्जाचा क्रमांक तपासा',
  'submit.submit': 'अर्ज सादर करा',

  // --- profile / register ------------------------------------------------------------------
  'profile.myDetails': 'माझी माहिती',
  'profile.loading': 'तुमची माहिती लोड होत आहे',
  'profile.recordGoneHint':
    'हे रेकॉर्ड आता अस्तित्वात नसल्यास (उदाहरणार्थ विकास डेटाबेस रीसेट झाल्यानंतर), ते येथे विसरा आणि तुमची माहिती पुन्हा नोंदवा.',
  'profile.forgetRecord': 'जतन केलेले रेकॉर्ड विसरा',
  'profile.name': 'नाव',
  'profile.nameDevanagari': 'नाव (देवनागरी)',
  'profile.fatherName': 'वडिलांचे नाव',
  'profile.dob': 'जन्मतारीख',

  'register.title': 'तुमची माहिती',
  'register.intro':
    'तुम्ही कोण आहात हे एकदाच सांगा. विभाग ही माहिती तुमची रेकॉर्ड जुळवण्यासाठी वापरतात, तुमच्यासाठी निर्णय घेण्यासाठी कधीही नाही. तुम्ही यापूर्वी नोंदणी केली असल्यास, हा फॉर्म सादर केल्यावर तुमचे विद्यमान रेकॉर्ड सापडेल.',
  'register.givenName': 'नाव',
  'register.familyName': 'आडनाव',
  'register.fatherName': 'वडिलांचे नाव',
  'register.fatherNameHint': 'ऐच्छिक. जुनी रेकॉर्ड जुळवण्यास मदत करते.',
  'register.nameDevanagari': 'देवनागरीत नाव',
  'register.optional': 'ऐच्छिक.',
  'register.dob': 'जन्मतारीख',
  'register.dobFuture': 'जन्मतारीख भविष्यातील असू शकत नाही.',
  'register.gender': 'लिंग',
  'register.genderPreferNot': 'सांगू इच्छित नाही',
  'register.genderFemale': 'स्त्री',
  'register.genderMale': 'पुरुष',
  'register.genderOther': 'इतर',
  'register.saving': 'जतन करत आहोत…',
  'register.saveContinue': 'जतन करा आणि पुढे जा',

  // --- applications list -------------------------------------------------------------------
  'apps.title': 'माझे अर्ज',
  'apps.loading': 'तुमचे अर्ज लोड होत आहेत',
  'apps.noneHeading': 'अजून येथे काहीही नाही',
  'apps.noneBody':
    'तुम्ही अजून कशासाठीही अर्ज केलेला नाही. तुम्ही केल्यावर, प्रत्येक विभागाच्या तपासणीचा माग तुम्ही येथे ठेवू शकता.',
  'apps.browseServices': 'सेवा पाहा',
  'apps.caption': 'तुमचे अर्ज',
  'apps.colNumber': 'अर्ज क्रमांक',
  'apps.colService': 'सेवा',
  'apps.colStatus': 'स्थिती',
  'apps.colDecisionDue': 'निर्णयाची मुदत',
  'apps.trackLabel': 'अर्ज क्रमांकाने माग ठेवा',
  'apps.track': 'माग ठेवा',

  // --- single application ------------------------------------------------------------------
  'app.loading': 'अर्ज लोड होत आहे',
  'app.notFoundTitle': 'अर्ज सापडला नाही',
  'app.notFoundPrefix': 'क्रमांक',
  'app.notFoundSuffix': 'असलेला कोणताही अर्ज तुमच्यासाठी सापडला नाही.',
  'app.myApplications': 'माझे अर्ज',
  'app.titlePrefix': 'अर्ज',
  'app.service': 'सेवा',
  'app.submitted': 'सादर केले',
  'app.decisionDue': 'निर्णयाची मुदत',
  'app.departmentChecks': 'विभागांच्या तपासण्या',
  'app.checksSoon': 'विभागांच्या तपासण्या येथे लवकरच दिसतील.',
  'app.stepFrom': '{dept} कडून',
  'app.received': '{date} रोजी मिळाले',
  'app.recordsFetched': 'तुमच्यासाठी मिळवलेली रेकॉर्ड',
  'app.recordsHint':
    'विभागांकडे सध्या आहेत तशी दाखवली आहेत. समन्वय त्यांची प्रत ठेवत नाही. विकास आवृत्ती: ही प्रात्यक्षिक विभाग प्रणालींमधून येतात.',
  'app.waitingForSystem': 'या विभाग प्रणालीची प्रतीक्षा आहे.',
  'app.refreshing': 'रिफ्रेश करत आहोत…',
  'app.refresh': 'रिफ्रेश करा',
  'app.selfRefreshHint': 'तुमचा अर्ज सुरू असताना हे पृष्ठ स्वतःहून रिफ्रेश होते.',
  'app.na': 'लागू नाही',

  // --- sanction panel ----------------------------------------------------------------------
  'sanction.heading': 'अर्ज मंजूर झाला',
  'sanction.disbursement': 'वितरण',
  'sanction.instalments': 'हप्ते',
  'sanction.sanctionedOn': 'मंजुरीची तारीख',
  'sanction.instalment': 'हप्ता {n}',

  // --- application status (lib/status.ts) --------------------------------------------------
  'status.submitted.short': 'सादर केले',
  'status.submitted.long': 'सादर केले: तुमचा अर्ज मिळाला असून तपासण्या सुरू होत आहेत',
  'status.partiallyVerified.short': 'सुरू आहे',
  'status.partiallyVerified.long': 'सुरू आहे: काही विभागांच्या रेकॉर्डची अजून प्रतीक्षा आहे',
  'status.verified.short': 'पडताळले',
  'status.verified.long': 'रेकॉर्ड पडताळले — अधिकाऱ्याच्या निर्णयाची प्रतीक्षा',
  'status.approved.short': 'मंजूर',
  'status.approved.long': 'मंजूर: तुमचा अर्ज मंजूर झाला',
  'status.closed.short': 'पूर्ण झाले',
  'status.closed.long': 'पूर्ण झाले: हा अर्ज बंद करण्यात आला आहे',
  'status.rejected.short': 'कारवाई आवश्यक',
  'status.rejected.long': 'कारवाई आवश्यक: हा अर्ज मंजूर झाला नाही',
  'status.failed.short': 'कारवाई आवश्यक',
  'status.failed.long': 'कारवाई आवश्यक: विभागाचे रेकॉर्ड मिळवता आले नाही',
  'status.inProgress.short': 'सुरू आहे',
  'status.inProgress.long': 'सुरू आहे: {detail}',

  'step.completed': 'मिळाले',
  'step.pendingSource': 'विभागाची प्रतीक्षा',
  'step.failed': 'मिळवता आले नाही, कारवाई आवश्यक',

  // --- API errors (ui/errors.ts) that citizens see ----------------------------------------
  'errors.sessionEnded': 'तुमचे सत्र संपले आहे. सुरू ठेवण्यासाठी पुन्हा साइन इन करा.',
  'errors.missingDepartments': 'सादर करण्यापूर्वी तुमची {departments} {accounts} जोडा.',
  'errors.departmentWord': 'विभागाची',
  'errors.accountOne': 'खाते',
  'errors.accountMany': 'खाती',
  'errors.linkProofInvalid': 'ती पडताळणी स्वीकारली गेली नाही. तपशील तपासा आणि पुन्हा प्रयत्न करा.',
  'errors.duplicateLocalId': 'ते विभाग ओळखपत्र आधीच दुसऱ्या व्यक्तीशी जोडलेले आहे.',
  'errors.noPriorAward':
    'या सेवेसाठी मागील वर्षीचा मंजूर लाभ आवश्यक आहे, आणि तुमच्यासाठी असा कोणताही आढळला नाही.',
  'errors.notApprovable': 'हा अर्ज अजून मंजूर करता येणार नाही — विभागाचे एक रेकॉर्ड अजून प्रलंबित आहे.',
  'errors.unreachable':
    'समन्वय सेवेशी संपर्क होऊ शकला नाही. अनुप्रयोग सुरू आहे का ते तपासा आणि पुन्हा प्रयत्न करा.',
  'errors.forbidden': 'तुम्हाला ते करण्याची परवानगी नाही.',
  'errors.notFound': 'त्या विनंतीसाठी काहीही आढळले नाही.',
  'errors.serverError': 'सेवा ती विनंती पूर्ण करू शकली नाही. थोड्या वेळाने पुन्हा प्रयत्न करा.',
  'errors.generic': 'विनंती पूर्ण करता आली नाही. पुन्हा प्रयत्न करा.',
  'errors.somethingWrong': 'काहीतरी चूक झाली. पुन्हा प्रयत्न करा.',
  'errors.tryAgain': 'पुन्हा प्रयत्न करा',
}
