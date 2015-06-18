angular.module('mrlapp.main.service', [
    'mrlapp.mrl',
    'mrlapp.main.statestoragesvc'
])

        .directive('serviceBody', [function () {
                return {
                    scope: {
                        fw: '=',
                        data: '=',
                        guidata: '=',
                        methods: '='
//                        inst: '=inst',
                    },
//                    controller: "@",  //Not working as I
//                    name: "ctrlName", //want it to
                    link: function (scope, elem, attr) {
//                        scope.fw = attr.inst.fw;
//                        scope.data = attr.inst.data;
//                        scope.guidata = attr.inst.guidata;
//                        scope.methods = attr.inst.methods;

                        scope.getContentUrl = function () {
                            //TODO: TEST THIS! - seems to not work as expected
                            var template = 'service/views/' + attr.type + 'gui.html';
                            return (angular.isDefined(template)) ? template : 'service/views/default.html';
//                            return 'service/views/' + attr.type + 'gui.html';
                        };
                    },
                    template: '<div ng-include="getContentUrl()"></div>'
                };
            }])

        .controller('ServiceCtrl', ['$scope', '$modal', 'mrl', 'StateStorageSvc',
            function ($scope, $modal, mrl, StateStorageSvc) {
                console.log('testing', $scope);

                //make sure $scope.service is there
                var listener = $scope.$watch(function () {
                    return $scope.service;
                }, function () {
                    if (!mrl.isUndefinedOrNull($scope.service.name)) {
                        listener();
                        init();
                    }
                });

                var init = function () {
                    //START_specific Service-Initialisation
                    //"inst" is given to the specific service-UI
                    $scope.inst = StateStorageSvc.getService($scope.service.name);
                    if ($scope.inst == null) {
                        $scope.inst = {};
                        $scope.inst.fw = {}; //framework-section - DO NOT WRITE IN THERE!
                        $scope.inst.data = mrl.getService($scope.service.name); //mrl-data-section
                        $scope.inst.guidata = {}; //data-section - write your data in there!
                        $scope.inst.methods = {}; //methods-section
                        StateStorageSvc.addService($scope.service.name, $scope.inst);
                        //should be able to delete this:
//                    $scope.inst.myService = mrl.services[$scope.name];
//                    mrl.addService($scope.name, $scope.inst);
                    }
                    $scope.fw = $scope.inst.fw;
                    $scope.data = $scope.inst.data;
                    $scope.guidata = $scope.inst.guidata;
                    $scope.methods = $scope.inst.methods;

                    //TODO: refactor
                    console.log("$scope,size", $scope.size);
                    if ($scope.size != null && $scope.size.lastIndexOf("force", 0) == 0) {
                        $scope.fw.oldsize = $scope.fw.size;
                        $scope.fw.size = $scope.size.substring(5, $scope.size.length);
                        $scope.fw.forcesize = true;
                    } else {
                        if ($scope.fw.oldsize != null) {
                            $scope.fw.size = $scope.fw.oldsize;
                            $scope.fw.oldsize = null;
                        }
                        $scope.fw.forcesize = false;
                    }
                    if (!$scope.fw.size) {
                        $scope.fw.size = "medium";
                        $scope.fw.oldsize = null;
                    }

                    //TODO: add whatever service-specific functions are needed (init, ...)
                    //attachGUI(), detachGUI(), send(method, data), sendTo(name, method, data),
                    //subscribe(inMethod, outMethod), subscribeTo(publisherName, inMethod, outMethod),
                    //key(inStr), releaseService(), serviceGUIInit(), broadcastState()
//                if ($scope.fw.send == null) {
//                    $scope.fw.send = function (method, data) {
//                        $scope.fw.sendTo($scope.name, method, data);
//                    };
//                    $scope.fw.sendTo = function (name, method, data) {
//                        mrl.sendTo(name, method, data);
//                    };
//                    $scope.fw.subscribe = function (inMethod, outMethod) {
//                        $scope.fw.subscribeTo($scope.name, inMethod, outMethod);
//                    };
//                    $scope.fw.subscribeTo = function (publisherName, inMethod, outMethod) {
//                        mrl.subscribeTo(publisherName, inMethod, outMethod);
//                    };
//                }
                    //to be overridden (fallback, if not)
//                if ($scope.methods.init == null) {
//                    $scope.methods.init = function () {
//                    };
//                }
//                if ($scope.methods.attachGUI == null) {
//                    $scope.methods.attachGUI = function () {
//                    };
//                }
//                if ($scope.methods.detachGUI == null) {
//                    $scope.methods.detachGUI = function () {
//                    };
//                }
                    //END_specific Service-Initialisation
                };

                //footer-size-change-buttons
                $scope.changesize = function (size) {
                    console.log("button clicked", size);
                    $scope.fw.oldsize = $scope.fw.size;
                    $scope.fw.size = size;
                    if (size == "full") {
                        //launch the service as a modal ('full')
                        var modalInstance = $modal.open({
                            animation: true,
                            templateUrl: 'views/servicefulltemplate.html',
                            controller: 'ServiceFullCtrl',
                            size: 'lg',
                            resolve: {
                                name: function () {
                                    return $scope.name;
                                },
                                type: function () {
                                    return $scope.type;
                                },
                                simpletype: function () {
                                    return $scope.simpletype;
                                },
                                inst: function () {
                                    return $scope.inst;
                                }
                            }
                        });
                        //modal closed -> recover to old size
                        modalInstance.result.then(function () {
                            $scope.fw.size = $scope.fw.oldsize;
                            $scope.fw.oldsize = null;
                        });
                    }
                };

//                angular.element(document).ready(function () {
//                    console.log('Hello World');
//                    mrl.registerForServices($scope.createService);
//                    mrl.connect(document.location.origin.toString() + '/api/messages');
//                });

            }])

        .controller('ServiceFullCtrl', function ($scope, $modalInstance, name, type, simpletype, inst) {
            //Controller for the modal (service-full)

            $scope.name = name;
            $scope.type = type;
            $scope.simpletype = simpletype;
            $scope.inst = inst;

            $scope.modal = true;

            console.log("servicefullctrl", $scope.name, $scope.type, $scope.simpletype, $scope.inst);

            $scope.close = function () {
                $modalInstance.close();
            };
        });